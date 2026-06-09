package com.aicall.service;

import com.aicall.common.CallStatus;
import com.aicall.common.DialogSlotHelper;
import com.aicall.common.ForcedHangupRules;
import com.aicall.common.HangupType;
import com.aicall.util.AsrTextNormalizer;
import com.aicall.config.AiVoiceProperties;
import com.aicall.entity.CallRecord;
import com.aicall.mapper.CallRecordMapper;
import com.aicall.dto.AiChatMessage;
import com.aicall.dto.AiChatRequest;
import com.aicall.dto.AiChatResponse;
import com.aicall.dto.HangupDecision;
import com.aicall.util.DialogTranscriptLog;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** 外呼接通后 ESL 人机对话：开场白 → 短段 ASR → LLM → 流式 TTS */
@Slf4j
@Service
@RequiredArgsConstructor
public class OutboundDialogLoopService {

    private final AiVoiceProperties aiVoiceProperties;
    private final FreeSwitchEslService eslService;
    private final CallSessionService callSessionService;
    private final CallAiVoiceService callAiVoiceService;
    private final AsrRecognitionService asrRecognitionService;
    private final CallDialogPersistService callDialogPersistService;
    private final CallUtteranceRecordService callUtteranceRecordService;
    private final VoicePlaybackService voicePlaybackService;
    private final OllamaChatService ollamaChatService;
    private final CallSessionRecordService callSessionRecordService;
    private final ForcedHangupService forcedHangupService;
    private final OpeningVoicePrewarmService openingVoicePrewarmService;
    private final OpeningVoiceCacheService openingVoiceCacheService;
    private final CallEndSummaryService callEndSummaryService;
    private final VoiceRuntimeSettingsService voiceRuntimeSettingsService;
    private final OpeningPlaybackService openingPlaybackService;
    private final HumanTransferService humanTransferService;
    private final CallRecordMapper callRecordMapper;
    private final OutboundDialogRegistry outboundDialogRegistry;
    private final DialogScriptPackRegistry dialogScriptPackRegistry;
    private final DialogCallContextService dialogCallContextService;
    private final DialogMainFlowService dialogMainFlowService;
    private final DialogTurnRegistry dialogTurnRegistry;

    private static final int MIN_SPEECH_WAV_BYTES = 4000;

    public void run(String uuid, Integer callRecordId) {
        if (!StringUtils.hasText(uuid) || callRecordId == null) {
            return;
        }
        outboundDialogRegistry.register(uuid);
        dialogTurnRegistry.register(uuid);
        log.info("[对话] run 入口 uuid={} recordId={}", uuid, callRecordId);
        Integer taskId = resolveTaskId(callRecordId);
        voiceRuntimeSettingsService.bindCallSilenceProfile(uuid, taskId);
        voiceRuntimeSettingsService.logEffectiveVoiceProfile("接通 uuid=" + uuid, taskId);
        long callStart = System.currentTimeMillis();
        int endCallStatus = CallStatus.CONNECTED;
        try {
            endCallStatus = runDialogLoop(uuid, callRecordId, callStart, taskId);
        } catch (Exception e) {
            log.warn("[对话] 异常 uuid={}: {}", uuid, e.getMessage());
        } finally {
            if (humanTransferService.isTransferred(callRecordId)) {
                callAiVoiceService.releaseCallResources(uuid);
                voicePlaybackService.releaseChannel(uuid);
                humanTransferService.finalizeAiHandoff(uuid, callRecordId, callStart);
                return;
            }
            voicePlaybackService.stopChannelPlayback(uuid);
            String recordUrl = callSessionRecordService.stopAndPublish(uuid, callRecordId);
            if (eslService.uuidExists(uuid)) {
                eslService.hangupChannel(uuid, "dialog-finally");
            }
            callAiVoiceService.releaseCallResources(uuid);
            voicePlaybackService.releaseChannel(uuid);
            endCall(uuid, callRecordId, callStart, recordUrl, endCallStatus);
            outboundDialogRegistry.unregister(uuid);
            dialogTurnRegistry.unregister(uuid);
            voiceRuntimeSettingsService.unbindCallSilenceProfile(uuid);
        }
    }

    private int runDialogLoop(String uuid, Integer callRecordId, long callStart, Integer taskId)
            throws Exception {
        if (!eslService.uuidExists(uuid)) {
            log.warn("[对话] 通道不存在 uuid={}", uuid);
            return CallStatus.CONNECTED;
        }
        eslService.ensureOutboundMediaReady(uuid);
        boolean openingPlayed = false;
        if (voiceRuntimeSettingsService.isPlayOpeningOnAnswer()) {
            openingPlayed = openingPlaybackService.playOpening(uuid, callRecordId);
            if (!openingPlayed) {
                log.warn("[对话] 开场白未播出 uuid={} recordId={}", uuid, callRecordId);
            }
        } else {
            log.info("[对话] 接通播报关闭，将由模型播报开场白 uuid={} recordId={}", uuid, callRecordId);
        }
        if (!callSessionRecordService.isSessionRecording(uuid)) {
            callSessionRecordService.startSessionRecord(uuid, callRecordId);
        }

        return runTurnBasedLoop(uuid, callRecordId, callStart, taskId, new ArrayList<>());
    }

    private Integer resolveTaskId(Integer callRecordId) {
        if (callRecordId == null) {
            return null;
        }
        CallRecord record = callRecordMapper.selectById(callRecordId);
        return record != null ? record.getTaskId() : null;
    }

    private int runTurnBasedLoop(String uuid, Integer callRecordId, long callStart, Integer taskId,
                                 List<AiChatMessage> history) throws Exception {
        if (voiceRuntimeSettingsService.isPlayOpeningOnAnswer()) {
            String opening = openingPlaybackService.resolveOpeningText();
            appendHistory(history, "assistant", opening);
            dialogTurnRegistry.afterOpeningPlayback(uuid);
            log.info("[对话] 预录开场白已播，等待客户说话 uuid={} recordId={}", uuid, callRecordId);
        } else {
            playModelOpeningTurnBased(uuid, callRecordId, history);
        }

        log.info("[分段对话开始] uuid={} recordId={} taskId={}", uuid, callRecordId, taskId);
        initMainFlowIfNeeded(callRecordId);
        int rounds = 0;
        int duplicateUserStreak = 0;
        int fillerOnlyStreak = 0;
        int emptyListenStreak = 0;
        int silenceProbeCount = 0;
        long lastAiSpeechMs = System.currentTimeMillis();
        boolean endedByHangup = false;
        int endCallStatus = CallStatus.CONNECTED;
        Boolean lastBusinessProbe = null;
        // 开场/上一轮已 waitPlaybackFinished + syncAsrBaseline，首轮无需再睡 tail
        boolean skipPlaybackTailWait = true;
        while (rounds < aiVoiceProperties.getDialogMaxRounds()) {
            if (!eslService.uuidExists(uuid) || outboundDialogRegistry.isCancelled(uuid)) {
                log.info("[对话] 循环退出 uuid={} rounds={} 通道存在={} 已取消={}",
                        uuid, rounds, eslService.uuidExists(uuid), outboundDialogRegistry.isCancelled(uuid));
                break;
            }
            int elapsed = (int) ((System.currentTimeMillis() - callStart) / 1000);
            if (elapsed >= aiVoiceProperties.getDialogMaxCallSec()) {
                log.info("[对话] 达到最大通话时长 {}s uuid={}", elapsed, uuid);
                playDurationHangup(uuid, callRecordId, history);
                endedByHangup = true;
                break;
            }

            if (!skipPlaybackTailWait) {
                Thread.sleep(aiVoiceProperties.resolveTurnBasedPlaybackTailMs());
                voicePlaybackService.stopChannelPlayback(uuid);
                callSessionRecordService.syncAsrBaselineAfterPlayback(uuid);
            }
            skipPlaybackTailWait = false;
            dialogTurnRegistry.userTakesFloor(uuid, "turn-based-wait-user");
            long listenStart = System.currentTimeMillis();
            AsrListenResult listen = recordAndRecognize(uuid);
            if (!StringUtils.hasText(listen.text())) {
                emptyListenStreak++;
                if (listen.speechWithoutRecognition()) {
                    log.warn("[ASR] 检测到语音但未识别 uuid={} round={} 播放「没听清」提示", uuid, rounds);
                    if (playTurnBasedNudge(uuid, callRecordId, history,
                            ForcedHangupRules.turnBasedUnclearAsrNudge())) {
                        lastAiSpeechMs = System.currentTimeMillis();
                        emptyListenStreak = 0;
                        skipPlaybackTailWait = true;
                    }
                } else if (aiVoiceProperties.isDialogSilenceProbeEnabled()
                        && silenceProbeCount < aiVoiceProperties.getDialogSilenceProbeMax()) {
                    int probeThreshold = aiVoiceProperties.resolveDialogSilenceProbeMs(silenceProbeCount + 1);
                    if (System.currentTimeMillis() - lastAiSpeechMs >= probeThreshold
                            && emptyListenStreak >= 1) {
                        silenceProbeCount++;
                        int kbId = DialogCallContextService.DEFAULT_KB_ID;
                        if (callRecordId != null) {
                            var ctx = dialogCallContextService.resolve(callRecordId);
                            if (ctx.hasKb()) {
                                kbId = ctx.getKbId();
                            }
                        }
                        String probe = dialogScriptPackRegistry.isMainFlowEnabled(kbId)
                                ? dialogScriptPackRegistry.silenceProbeText(silenceProbeCount, kbId)
                                : ForcedHangupRules.turnBasedSilenceProbe(silenceProbeCount);
                        log.info("[静默] uuid={} 追问 {}/{} threshold={}ms text={}",
                                uuid, silenceProbeCount, aiVoiceProperties.getDialogSilenceProbeMax(),
                                probeThreshold, probe);
                        if (playTurnBasedNudge(uuid, callRecordId, history, probe)) {
                            lastAiSpeechMs = System.currentTimeMillis();
                            emptyListenStreak = 0;
                            skipPlaybackTailWait = true;
                            if (silenceProbeCount >= aiVoiceProperties.getDialogSilenceProbeMax()) {
                                log.info("[静默] 已达最大追问次数，无人应答挂机 uuid={} count={}",
                                        uuid, silenceProbeCount);
                                endedByHangup = true;
                                endCallStatus = CallStatus.NO_ANSWER;
                                eslService.hangupChannel(uuid, "silence-probe-max");
                                break;
                            }
                        }
                    }
                } else {
                    log.warn("[对话] 客户未说完或未识别到语音 uuid={} round={}，继续监听", uuid, rounds);
                }
                rounds++;
                continue;
            }
            emptyListenStreak = 0;
            silenceProbeCount = 0;
            String userText = AsrTextNormalizer.normalize(listen.text());
            long asrElapsed = System.currentTimeMillis() - listenStart;
            log.info("[ASR] uuid={} text={} 耗时={}ms 静默=false", uuid, userText, asrElapsed);
            if (DialogSlotHelper.isPunctuationOnly(userText)) {
                log.info("[对话] ASR 无实质内容，继续监听 uuid={} text={}", uuid, userText);
                callSessionRecordService.syncAsrBaselineAfterPlayback(uuid);
                rounds++;
                continue;
            }
            String lastAi = lastAssistantText(history);
            if (ForcedHangupRules.isLikelyAsrEcho(userText, lastAi)) {
                log.warn("[对话] 疑似 TTS 回声，跳过本轮 ASR uuid={} text={}", uuid, userText);
                callSessionRecordService.syncAsrBaselineAfterPlayback(uuid);
                continue;
            }
            if (DialogSlotHelper.isFillerOnly(userText)) {
                fillerOnlyStreak++;
                if (fillerOnlyStreak >= 5) {
                    log.info("[对话] 客户连续语气词过多，结束对话 uuid={}", uuid);
                    endedByHangup = true;
                    eslService.hangupChannel(uuid, "filler-streak");
                    break;
                }
            } else {
                fillerOnlyStreak = 0;
            }
            if (isDuplicateUserUtterance(history, userText)) {
                duplicateUserStreak++;
                log.warn("[对话] 客户重复短答 uuid={} text={} streak={}", uuid, userText, duplicateUserStreak);
                if (duplicateUserStreak >= 3 || !eslService.uuidExists(uuid)) {
                    log.info("[对话] 客户重复应答过多或通道已断，结束对话 uuid={}", uuid);
                    if (eslService.uuidExists(uuid)) {
                        endedByHangup = true;
                        eslService.hangupChannel(uuid, "duplicate-user");
                    }
                    break;
                }
                log.info("[对话] 客户重复短答，继续监听 uuid={} streak={}", uuid, duplicateUserStreak);
                rounds++;
                continue;
            }
            duplicateUserStreak = 0;
            dialogTurnRegistry.forceUserTurnReady(uuid, voiceRuntimeSettingsService.resolveUserSilenceMs(uuid));
            callDialogPersistService.appendUser(callRecordId, userText);
            if (humanTransferService.onUserSpeech(uuid, callRecordId, resolveCustomerPhone(callRecordId), userText)) {
                log.info("[风控] 关键词命中转人工 uuid={} recordId={}", uuid, callRecordId);
                return CallStatus.CONNECTED;
            }
            appendHistory(history, "user", userText);

            if (ForcedHangupRules.isUserFarewell(userText)) {
                String goodbye = DialogSlotHelper.goodbyeReply(history);
                log.info("[对话] 客户告别 uuid={} text={}", uuid, userText);
                playFarewellAndHangup(uuid, callRecordId, history, goodbye);
                endedByHangup = true;
                break;
            }

            AiChatRequest turn = new AiChatRequest();
            turn.setUserText(userText);
            turn.setCallRecordId(callRecordId);
            turn.setFsUuid(uuid);
            List<AiChatMessage> callHistory = callDialogPersistService.loadChatHistory(callRecordId);
            turn.setHistory(new ArrayList<>(callHistory));
            log.info("[对话上下文] recordId={} uuid={} 本通历史条数={}", callRecordId, uuid, callHistory.size());
            turn.setBusinessProbeThisTurn(lastBusinessProbe);
            if (!eslService.uuidExists(uuid) || outboundDialogRegistry.isCancelled(uuid)) {
                log.info("[对话] 通道已断开或已取消，停止对话 uuid={}", uuid);
                break;
            }
            long replyStart = System.currentTimeMillis();
            if (!dialogTurnRegistry.mayAiSpeak(uuid)) {
                log.warn("[对话] 轮次门控拒绝 AI 播报 uuid={} speaker={}", uuid, dialogTurnRegistry.getSpeaker(uuid));
                rounds++;
                continue;
            }
            dialogTurnRegistry.aiTakesFloor(uuid);
            AiChatResponse resp = callAiVoiceService.voiceTurn(turn);
            long llmElapsed = System.currentTimeMillis() - replyStart;
            log.info("[LLM] uuid={} model={} 耗时={}ms replyLen={} hangup={}",
                    uuid, resp.getModel(), llmElapsed,
                    resp.getReply() != null ? resp.getReply().length() : 0,
                    Boolean.TRUE.equals(resp.getShouldHangup()));
            if (!eslService.uuidExists(uuid)) {
                log.info("[对话] 通道已断开（可能 FS 通话超时或客户挂机），停止对话 uuid={}", uuid);
                break;
            }
            lastBusinessProbe = resp.getBusinessProbeNext();
            String reply = resp.getReply();
            if (!StringUtils.hasText(reply)) {
                log.warn("[对话] LLM 应答为空 uuid={} recordId={} model={}",
                        uuid, callRecordId, resp.getModel());
            }
            if (StringUtils.hasText(reply)) {
                String played = Boolean.TRUE.equals(resp.getShouldHangup())
                        && StringUtils.hasText(resp.getEndWords()) ? resp.getEndWords() : reply;
                callDialogPersistService.appendAssistant(callRecordId, played);
                if (eslService.uuidExists(uuid)) {
                    boolean bargeIn = Boolean.TRUE.equals(resp.getPlaybackWaitHandled())
                            ? false
                            : waitPlaybackWithBargeIn(uuid, played);
                    callSessionRecordService.syncAsrBaselineAfterPlayback(uuid);
                    if (bargeIn) {
                        dialogTurnRegistry.userTakesFloor(uuid, "turn-based-barge-in");
                    } else {
                        dialogTurnRegistry.aiYieldsFloor(uuid);
                    }
                    skipPlaybackTailWait = true;
                }
                appendHistory(history, "assistant", reply);
                lastAiSpeechMs = System.currentTimeMillis();
            }
            rounds++;
            if (!eslService.uuidExists(uuid)) {
                break;
            }
            if (Boolean.TRUE.equals(resp.getShouldHangup())) {
                log.info("[对话] 规则挂断 uuid={}", uuid);
                endedByHangup = true;
                eslService.hangupChannel(uuid, "rule-hangup");
                break;
            }
            if (!eslService.uuidExists(uuid)) {
                break;
            }
        }

        if (!endedByHangup && endCallStatus != CallStatus.NO_ANSWER && eslService.uuidExists(uuid)) {
            log.info("[对话] 轮次用尽或循环结束，播放告别语 uuid={} rounds={}", uuid, rounds);
            playGracefulLoopEnd(uuid, callRecordId, history);
            endedByHangup = true;
        }
        return endCallStatus;
    }

    private record AsrListenResult(String text, boolean speechWithoutRecognition) {}

    private AsrListenResult recordAndRecognize(String uuid) throws Exception {
        if (aiVoiceProperties.isAsrVadEnabled()) {
            try {
                Path wav = callUtteranceRecordService.recordUntilSilence(uuid);
                long size = Files.size(wav);
                log.info("[对话] 录音完成 uuid={} bytes={}", uuid, size);
                String text = asrRecognitionService.recognize(wav);
                if (StringUtils.hasText(text)) {
                    return new AsrListenResult(text, false);
                }
                return new AsrListenResult("", size >= MIN_SPEECH_WAV_BYTES);
            } catch (NoSpeechDetectedException e) {
                log.debug("[对话] 未检测到客户说话 uuid={}，继续监听", uuid);
                return new AsrListenResult("", false);
            } catch (Exception e) {
                if (!NoSpeechDetectedException.isNoSpeech(e)) {
                    log.warn("[对话] VAD 录音失败 uuid={}: {}", uuid, e.getMessage());
                }
                return new AsrListenResult("", false);
            }
        }
        if (aiVoiceProperties.isAsrStreamEnabled() && !"sentence".equalsIgnoreCase(aiVoiceProperties.getAsrMode())) {
            return new AsrListenResult(recordStreamAsr(uuid), false);
        }
        return new AsrListenResult(recognizeChunk(uuid, aiVoiceProperties.getDialogRecordChunkSec()), false);
    }

    private boolean playTurnBasedNudge(String uuid, Integer callRecordId,
                                       List<AiChatMessage> history, String text) throws Exception {
        if (!eslService.uuidExists(uuid) || outboundDialogRegistry.isCancelled(uuid)) {
            return false;
        }
        // 监听轮开始时 userTakesFloor 占位；静默/没听清追问前须释放 USER 轮次
        dialogTurnRegistry.forceUserTurnReady(uuid);
        if (!dialogTurnRegistry.mayAiSpeak(uuid)) {
            log.warn("[对话] 轮次门控拒绝追问 uuid={} speaker={}", uuid, dialogTurnRegistry.getSpeaker(uuid));
            return false;
        }
        dialogTurnRegistry.aiTakesFloor(uuid);
        DialogTranscriptLog.aiReply(callRecordId, uuid, text, "nudge", false);
        callDialogPersistService.appendAssistant(callRecordId, text);
        callAiVoiceService.playText(uuid, text);
        waitPlaybackWithBargeIn(uuid, text);
        callSessionRecordService.syncAsrBaselineAfterPlayback(uuid);
        appendHistory(history, "assistant", text);
        dialogTurnRegistry.aiYieldsFloor(uuid);
        return true;
    }

    private void playFarewellAndHangup(String uuid, Integer callRecordId,
                                       List<AiChatMessage> history, String goodbye) {
        try {
            DialogTranscriptLog.aiReply(callRecordId, uuid, goodbye, "farewell", true);
            callDialogPersistService.appendAssistant(callRecordId, goodbye);
            callAiVoiceService.playFixedEnding(uuid, callRecordId, goodbye);
            voicePlaybackService.waitPlaybackFinished(uuid, goodbye);
            appendHistory(history, "assistant", goodbye);
            eslService.hangupChannel(uuid, "farewell");
        } catch (Exception e) {
            log.warn("[对话] 告别挂断失败 uuid={}: {}", uuid, e.getMessage());
            eslService.hangupChannel(uuid, "farewell-error");
        }
    }

    /** 短段连续识别，有结果即返回，不等整句录满 */
    private String recordStreamAsr(String uuid) throws Exception {
        int chunkSec = Math.max(1, aiVoiceProperties.getDialogRecordChunkSec());
        int maxAttempts = 4;
        StringBuilder acc = new StringBuilder();
        for (int i = 0; i < maxAttempts; i++) {
            if (!eslService.uuidExists(uuid)) {
                break;
            }
            String part = recognizeChunk(uuid, chunkSec);
            if (!StringUtils.hasText(part)) {
                continue;
            }
            acc.append(part);
            if (endsWithSentence(part) || part.length() >= 6) {
                return acc.toString().trim();
            }
        }
        return acc.toString().trim();
    }

    private static boolean endsWithSentence(String text) {
        if (!StringUtils.hasText(text)) {
            return false;
        }
        char c = text.charAt(text.length() - 1);
        return c == '。' || c == '！' || c == '？' || c == '.' || c == '!' || c == '?';
    }

    private String recognizeChunk(String uuid, int sec) throws Exception {
        try {
            Path wav = callUtteranceRecordService.recordFixedSeconds(uuid, sec);
            log.info("[对话] 固定段录音 uuid={} bytes={}", uuid, Files.size(wav));
            return asrRecognitionService.recognize(wav);
        } catch (Exception e) {
            log.warn("[对话] 固定段录音/ASR 失败 uuid={} sec={}: {}", uuid, sec, e.getMessage());
            return "";
        }
    }

    private void playModelOpeningTurnBased(String uuid, Integer callRecordId,
                                           List<AiChatMessage> history) throws Exception {
        String opening = openingPlaybackService.resolveOpeningText();
        log.info("[对话] 接通播报关闭，TTS 播报开场白 uuid={} recordId={}", uuid, callRecordId);
        DialogTranscriptLog.opening(callRecordId, uuid, opening);
        callDialogPersistService.appendAssistant(callRecordId, opening);
        callAiVoiceService.playText(uuid, opening);
        waitPlaybackWithBargeIn(uuid, opening);
        callSessionRecordService.syncAsrBaselineAfterPlayback(uuid);
        appendHistory(history, "assistant", opening);
    }

    private static final String LOOP_END_WORDS =
            "好的，今天先聊到这儿，有需要随时联系我们，祝您生活愉快，再见。";

    private void playGracefulLoopEnd(String uuid, Integer callRecordId, List<AiChatMessage> history) {
        try {
            DialogTranscriptLog.aiReply(callRecordId, uuid, LOOP_END_WORDS, "loop-end", true);
            callDialogPersistService.appendAssistant(callRecordId, LOOP_END_WORDS);
            callAiVoiceService.playFixedEnding(uuid, callRecordId, LOOP_END_WORDS);
            voicePlaybackService.waitPlaybackFinished(uuid, LOOP_END_WORDS);
            appendHistory(history, "assistant", LOOP_END_WORDS);
            eslService.hangupChannel(uuid, "loop-end");
        } catch (Exception e) {
            log.warn("[对话] 轮次结束告别语失败 uuid={}: {}", uuid, e.getMessage());
            eslService.hangupChannel(uuid, "loop-end-error");
        }
    }

    private static boolean isDuplicateUserUtterance(List<AiChatMessage> history, String userText) {
        if (!StringUtils.hasText(userText) || history == null || history.isEmpty()) {
            return false;
        }
        if (AsrTextNormalizer.isNumericAmountUtterance(userText)) {
            return false;
        }
        String norm = normalizeUtterance(userText);
        String lastUser = "";
        for (int i = history.size() - 1; i >= 0; i--) {
            AiChatMessage m = history.get(i);
            if ("user".equalsIgnoreCase(m.getRole()) && StringUtils.hasText(m.getContent())) {
                lastUser = m.getContent();
                break;
            }
        }
        if (!StringUtils.hasText(lastUser)) {
            return false;
        }
        if (DialogSlotHelper.isFillerOnly(userText)) {
            return norm.equals(normalizeUtterance(lastUser));
        }
        if (norm.length() < 2) {
            return false;
        }
        return norm.equals(normalizeUtterance(lastUser));
    }

    private static String normalizeUtterance(String text) {
        return text.trim()
                .replaceAll("[\\s，,。.!！?？~～]+", "")
                .toLowerCase();
    }

    private void playDurationHangup(String uuid, Integer callRecordId, List<AiChatMessage> history) {
        try {
            HangupDecision dec = forcedHangupService.evaluateBeforeAi(callRecordId, null, null, null);
            String goodbye = StringUtils.hasText(dec.getEndWords())
                    ? dec.getEndWords() : ForcedHangupRules.DURATION_END_WORDS;
            DialogTranscriptLog.aiReply(callRecordId, uuid, goodbye, "timeout", true);
            callDialogPersistService.appendAssistant(callRecordId, goodbye);
            callAiVoiceService.playFixedEnding(uuid, callRecordId, goodbye);
            voicePlaybackService.waitPlaybackFinished(uuid, goodbye);
            appendHistory(history, "assistant", goodbye);
            eslService.hangupChannel(uuid, "duration-timeout");
        } catch (Exception e) {
            log.warn("[对话] 超时结束语播放失败 uuid={}: {}", uuid, e.getMessage());
            eslService.hangupChannel(uuid, "duration-timeout-error");
        }
    }

    private void endCall(String uuid, Integer callRecordId, long callStart, String recordUrl, int callStatus) {
        try {
            int duration = (int) ((System.currentTimeMillis() - callStart) / 1000);
            CallSessionService.EndReq end = new CallSessionService.EndReq();
            end.setCallRecordId(callRecordId);
            end.setFsUuid(uuid);
            end.setCallDuration(duration);
            end.setCallStatus(callStatus);
            if (callStatus == CallStatus.NO_ANSWER) {
                end.setHangupType(HangupType.NO_RESPONSE);
            }
            String dialogText = callDialogPersistService.getDialogText(callRecordId);
            end.setDialogText(dialogText);
            if (StringUtils.hasText(recordUrl)) {
                end.setRecordUrl(recordUrl);
            }
            callEndSummaryService.fillEndReqFromDialog(end, dialogText, duration);
            callSessionService.endSession(end);
            log.info("[对话] 通话结束 uuid={} duration={}s recordUrl={}", uuid, duration, recordUrl);
        } catch (Exception e) {
            log.warn("[对话] 结算失败 uuid={}: {}", uuid, e.getMessage());
        }
    }

    private void initMainFlowIfNeeded(Integer callRecordId) {
        if (callRecordId == null) {
            return;
        }
        var ctx = dialogCallContextService.resolve(callRecordId);
        int kbId = ctx.hasKb() ? ctx.getKbId() : DialogCallContextService.DEFAULT_KB_ID;
        dialogMainFlowService.ensureInit(callRecordId, kbId);
    }

    private void appendHistory(List<AiChatMessage> history, String role, String content) {
        AiChatMessage m = new AiChatMessage();
        m.setRole(role);
        m.setContent(content);
        history.add(m);
    }

    private static String lastAssistantText(List<AiChatMessage> history) {
        if (history == null) {
            return "";
        }
        for (int i = history.size() - 1; i >= 0; i--) {
            AiChatMessage m = history.get(i);
            if ("assistant".equalsIgnoreCase(m.getRole()) && StringUtils.hasText(m.getContent())) {
                return m.getContent();
            }
        }
        return "";
    }

    private boolean waitPlaybackWithBargeIn(String uuid, String text) throws Exception {
        if (!aiVoiceProperties.isBargeInEnabled() || !aiVoiceProperties.isBargeInDuringTurnBased()) {
            voicePlaybackService.waitPlaybackFinished(uuid, text, null);
            return false;
        }
        return voicePlaybackService.waitPlaybackFinished(uuid, text, () -> {
            if (outboundDialogRegistry.isCancelled(uuid) || !eslService.uuidExists(uuid)) {
                return true;
            }
            if (!aiVoiceProperties.isBargeInEnabled()) {
                return false;
            }
            if (callUtteranceRecordService.detectBargeInFromSession(uuid)) {
                dialogTurnRegistry.userTakesFloor(uuid, "playback-barge-in");
                voicePlaybackService.stopChannelPlayback(uuid);
                return true;
            }
            return false;
        });
    }

    private String resolveCustomerPhone(Integer callRecordId) {
        if (callRecordId == null) {
            return null;
        }
        CallRecord record = callRecordMapper.selectById(callRecordId);
        return record != null ? record.getCustomerPhone() : null;
    }
}
