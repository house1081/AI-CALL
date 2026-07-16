package com.aicall.service;

import com.aicall.common.TtsSynthesisException;
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
import com.aicall.dto.CallSessionMeta;
import com.aicall.dto.HangupDecision;
import com.aicall.dto.PrerecordTurnResultDto;
import com.aicall.service.prerecord.PrerecordCircuitService;
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
    private final TtsFailureRecoveryService ttsFailureRecoveryService;
    private final PrerecordCircuitService prerecordCircuitService;
    private final KbRecordingOutboundService kbRecordingOutboundService;
    private final RecordingOnlyPlaybackService recordingOnlyPlaybackService;
    private final CallSessionMetaService callSessionMetaService;
    private final CallContextCacheService callContextCacheService;
    private final CallPlaybackDedupService callPlaybackDedupService;

    private static final int MIN_SPEECH_WAV_BYTES = 4000;

    /** 本线程当前通话是否已播过礼貌挂机语（避免 finally 重复播报） */
    private final ThreadLocal<Boolean> politeEndingPlayed = ThreadLocal.withInitial(() -> false);

    public void run(String uuid, Integer callRecordId) {
        if (!StringUtils.hasText(uuid) || callRecordId == null) {
            return;
        }
        politeEndingPlayed.set(false);
        log.info("[对话] run 入口 uuid={} recordId={} thread={}",
                uuid, callRecordId, Thread.currentThread().getName());
        outboundDialogRegistry.register(uuid);
        dialogTurnRegistry.register(uuid);
        callDialogPersistService.bindCall(callRecordId);
        callSessionMetaService.bind(callRecordId);
        callContextCacheService.bind(callRecordId);
        log.debug("[对话] 会话快照已绑定 uuid={} recordId={}", uuid, callRecordId);
        Integer taskId = resolveTaskId(callRecordId);
        voiceRuntimeSettingsService.bindCallSilenceProfile(uuid, taskId);
        prerecordCircuitService.bindCall(uuid);
        voiceRuntimeSettingsService.logEffectiveVoiceProfile("接通 uuid=" + uuid, taskId);
        long callStart = System.currentTimeMillis();
        int endCallStatus = CallStatus.CONNECTED;
        try {
            endCallStatus = runDialogLoop(uuid, callRecordId, callStart, taskId);
        } catch (Exception e) {
            TtsSynthesisException tts = TtsSynthesisException.unwrap(e);
            if (tts != null) {
                ttsFailureRecoveryService.playEndingAndHangup(uuid, callRecordId, tts);
                endCallStatus = CallStatus.CONNECTED;
            } else {
                log.warn("[对话] 异常 uuid={}: {}", uuid, e.getMessage());
            }
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
                if (!Boolean.TRUE.equals(politeEndingPlayed.get())) {
                    ttsFailureRecoveryService.playEndingThenHangup(
                            uuid, callRecordId, ForcedHangupRules.END_WORDS, "dialog-finally");
                } else {
                    eslService.hangupChannel(uuid, "dialog-finally");
                }
            }
            callAiVoiceService.releaseCallResources(uuid);
            voicePlaybackService.releaseChannel(uuid);
            endCall(uuid, callRecordId, callStart, recordUrl, endCallStatus);
            outboundDialogRegistry.unregister(uuid);
            dialogTurnRegistry.unregister(uuid);
            voiceRuntimeSettingsService.unbindCallSilenceProfile(uuid);
            prerecordCircuitService.clearCall(uuid);
            callDialogPersistService.flushToDb(callRecordId);
            callDialogPersistService.unbindCall(callRecordId);
            callSessionMetaService.unbind(callRecordId);
            callContextCacheService.unbind(callRecordId);
            callPlaybackDedupService.clear(callRecordId);
            recordingOnlyPlaybackService.cancelPlaybackWatch(uuid);
            dialogMainFlowService.clearCall(callRecordId);
            politeEndingPlayed.remove();
        }
    }

    private int runDialogLoop(String uuid, Integer callRecordId, long callStart, Integer taskId)
            throws Exception {
        if (!eslService.uuidExists(uuid)) {
            log.warn("[对话] 通道不存在 uuid={}", uuid);
            return CallStatus.CONNECTED;
        }
        boolean openingPlayed = false;
        if (!callSessionRecordService.isSessionRecording(uuid)) {
            callSessionRecordService.startSessionRecord(uuid, callRecordId);
        }
        if (voiceRuntimeSettingsService.isPlayOpeningOnAnswer()) {
            openingPlayed = openingPlaybackService.playOpening(uuid, callRecordId);
            if (!openingPlayed) {
                log.warn("[对话] 开场白未播出 uuid={} recordId={}", uuid, callRecordId);
            }
        } else {
            log.info("[对话] 接通播报关闭，将由模型播报开场白 uuid={} recordId={}", uuid, callRecordId);
        }

        return runTurnBasedLoop(uuid, callRecordId, callStart, taskId, new ArrayList<>(), openingPlayed);
    }

    private Integer resolveTaskId(Integer callRecordId) {
        if (callRecordId == null) {
            return null;
        }
        CallSessionMeta meta = callSessionMetaService.get(callRecordId);
        if (meta != null && meta.getTaskId() != null) {
            return meta.getTaskId();
        }
        CallRecord record = callRecordMapper.selectById(callRecordId);
        return record != null ? record.getTaskId() : null;
    }

    private int runTurnBasedLoop(String uuid, Integer callRecordId, long callStart, Integer taskId,
                                 List<AiChatMessage> history, boolean openingAlreadyPlayed) throws Exception {
        if (voiceRuntimeSettingsService.isPlayOpeningOnAnswer()) {
            String opening = openingPlaybackService.resolveOpeningText();
            appendHistory(history, "assistant", opening);
            dialogTurnRegistry.afterOpeningPlayback(uuid);
            if (!openingAlreadyPlayed) {
                try {
                    voicePlaybackService.waitPlaybackFinished(uuid, opening);
                } catch (Exception e) {
                    log.debug("[对话] 等待开场白播完 uuid={}: {}", uuid, e.getMessage());
                }
            } else {
                log.info("[对话] 开场白已在 playOpening/摘机即播 阶段等待播完，跳过重复等待 uuid={}", uuid);
            }
            callSessionRecordService.syncAsrBaselineAfterPlayback(uuid);
            log.info("[对话] 预录开场白已播，等待客户说话 uuid={} recordId={}", uuid, callRecordId);
        } else {
            playModelOpeningTurnBased(uuid, callRecordId, history);
        }

        log.info("[分段对话开始] uuid={} recordId={} taskId={} mode={}",
                uuid, callRecordId, taskId,
                voiceRuntimeSettingsService.isSmartPrerecordMode() ? "smart_prerecord" : "ai_realtime");
        initMainFlowIfNeeded(callRecordId);
        int rounds = 0;
        int duplicateUserStreak = 0;
        int fillerOnlyStreak = 0;
        int emptyListenStreak = 0;
        int silenceProbeCount = 0;
        boolean silenceMainFlowReplayUsed = false;
        long lastAiSpeechMs = System.currentTimeMillis();
        boolean endedByHangup = false;
        int endCallStatus = CallStatus.CONNECTED;
        Boolean lastBusinessProbe = null;
        // 开场/上一轮已 waitPlaybackFinished + syncAsrBaseline，首轮无需再睡 tail
        boolean skipPlaybackTailWait = true;
        while (rounds < aiVoiceProperties.getDialogMaxRounds()) {
            if (!eslService.uuidExists(uuid) || outboundDialogRegistry.isCancelled(uuid)) {
                log.info("[对话] 循环退出 uuid={} rounds={} reason={} 通道存在={} 已取消={}",
                        uuid, rounds, dialogLoopExitReason(uuid, rounds, endedByHangup),
                        eslService.uuidExists(uuid), outboundDialogRegistry.isCancelled(uuid));
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
            }
            boolean userHoldingFloor = dialogTurnRegistry.getSpeaker(uuid)
                    == DialogTurnRegistry.ActiveSpeaker.USER;
            if (!userHoldingFloor) {
                awaitMinGapAfterAiSpeech(lastAiSpeechMs);
            }
            recordingOnlyPlaybackService.awaitOutboundPlaybackReady(uuid);
            voicePlaybackService.stopChannelPlayback(uuid);
            if (!userHoldingFloor) {
                callSessionRecordService.syncAsrBaselineAfterPlayback(uuid);
            }
            skipPlaybackTailWait = false;
            if (!userHoldingFloor) {
                dialogTurnRegistry.userTakesFloor(uuid, "turn-based-wait-user");
            }
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
                                if (!silenceMainFlowReplayUsed
                                        && tryReplayMainFlowQuestion(uuid, callRecordId, history)) {
                                    silenceMainFlowReplayUsed = true;
                                    silenceProbeCount = 0;
                                    log.info("[静默] 重播当前主线问题，继续等待客户应答 uuid={}", uuid);
                                } else {
                                    log.info("[静默] 已达最大追问次数，无人应答挂机 uuid={} count={}",
                                            uuid, silenceProbeCount);
                                    endedByHangup = true;
                                    endCallStatus = CallStatus.NO_ANSWER;
                                    playEndingThenHangup(
                                            uuid, callRecordId, ForcedHangupRules.SILENCE_END_WORDS, "silence-probe-max");
                                    break;
                                }
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
            log.info("[ASR] uuid={} text={} 听音={}ms ASR={}ms 合计={}ms",
                    uuid, userText, listen.listenMs(), listen.asrMs(), asrElapsed);
            if (DialogSlotHelper.isPunctuationOnly(userText)) {
                log.info("[对话] ASR 无实质内容，继续监听 uuid={} text={}", uuid, userText);
                callSessionRecordService.syncAsrBaselineAfterPlayback(uuid);
                rounds++;
                continue;
            }
            if (DialogSlotHelper.shouldIgnoreShortAsrUtterance(userText)) {
                log.info("[对话] 忽略过短/无意义 ASR，继续监听 uuid={} text={}", uuid, userText);
                callSessionRecordService.syncAsrBaselineAfterPlayback(uuid);
                rounds++;
                continue;
            }
            String lastAi = lastAssistantText(history);
            if (isLikelyEchoFromAssistant(userText, history)) {
                log.warn("[对话] 疑似机器人录音回声，跳过本轮 ASR uuid={} text={}", uuid, userText);
                callSessionRecordService.syncAsrBaselineAfterPlayback(uuid);
                continue;
            }
            if (ForcedHangupRules.isLikelyAsrEcho(userText, lastAi)) {
                log.warn("[对话] 疑似 TTS 回声，跳过本轮 ASR uuid={} text={}", uuid, userText);
                callSessionRecordService.syncAsrBaselineAfterPlayback(uuid);
                continue;
            }
            if (ForcedHangupRules.isLikelyOpeningEchoFragment(userText, lastAi)) {
                log.warn("[对话] 疑似开场白回声，跳过本轮 ASR uuid={} text={}", uuid, userText);
                callSessionRecordService.syncAsrBaselineAfterPlayback(uuid);
                continue;
            }
            if (DialogSlotHelper.isFillerOnly(userText)) {
                fillerOnlyStreak++;
                if (fillerOnlyStreak >= 5) {
                    log.info("[对话] 客户连续语气词过多，结束对话 uuid={}", uuid);
                    endedByHangup = true;
                    playEndingThenHangup(
                            uuid, callRecordId, LOOP_END_WORDS, "filler-streak");
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
                        playEndingThenHangup(
                                uuid, callRecordId, LOOP_END_WORDS, "duplicate-user");
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
            turn.setHistory(new ArrayList<>(history));
            log.info("[对话上下文] recordId={} uuid={} 本通历史条数={}", callRecordId, uuid, history.size());
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
            prerecordCircuitService.recordOffTopicComplaint(uuid, userText);

            boolean smartPrerecord = voiceRuntimeSettingsService.isSmartPrerecordMode();
            boolean useKbRecording = smartPrerecord
                    || prerecordCircuitService.shouldUsePrerecord(uuid, false);
            if (useKbRecording) {
                int kbId = resolveKbId(callRecordId);
                PrerecordTurnResultDto pre = kbRecordingOutboundService.handleTurn(
                        uuid, callRecordId, resolveCustomerPhone(callRecordId), userText, kbId, !smartPrerecord);
                if (smartPrerecord && (!pre.isHandled() || KbRecordingOutboundService.isSilentPrerecordResult(pre))) {
                    log.warn("[知识库录音] 应答未播出 uuid={} model={}，自动恢复",
                            uuid, pre.getModel());
                    pre = kbRecordingOutboundService.recoverUnhandledTurn(
                            uuid, callRecordId, userText, kbId);
                }
                if (pre.isHandled()) {
                    if (StringUtils.hasText(pre.getReplyText())) {
                        callDialogPersistService.appendAssistant(callRecordId, pre.getReplyText());
                        appendHistory(history, "assistant", pre.getReplyText());
                        lastAiSpeechMs = System.currentTimeMillis();
                    }
                    if (pre.isShouldHangup()) {
                        endedByHangup = true;
                        boolean alreadyPlayed = pre.isPlaybackWaitHandled()
                                && StringUtils.hasText(pre.getReplyText())
                                && (pre.getModel() == null || !pre.getModel().contains("-silent"));
                        if (alreadyPlayed) {
                            politeEndingPlayed.set(true);
                            awaitOutboundPlaybackBeforeHangup(uuid);
                            if (eslService.uuidExists(uuid)) {
                                eslService.hangupChannel(uuid, "kb-recording-refuse");
                            }
                        } else {
                            playEndingThenHangup(uuid, callRecordId,
                                    ForcedHangupRules.resolvePoliteEndWords(pre.getReplyText()),
                                    "kb-recording-refuse");
                        }
                        break;
                    }
                    boolean userInterrupted = dialogTurnRegistry.getSpeaker(uuid)
                            == DialogTurnRegistry.ActiveSpeaker.USER;
                    if (userInterrupted) {
                        log.info("[对话] 知识库播报被插嘴打断 uuid={}，继续听用户", uuid);
                    } else if (!recordingOnlyPlaybackService.shouldAsyncPlayback()) {
                        dialogTurnRegistry.aiYieldsFloor(uuid);
                        callSessionRecordService.syncAsrBaselineAfterPlayback(uuid);
                    }
                    // async 模式：基线同步与 aiYieldsFloor 由 RecordingOnlyPlaybackService 后台线程处理
                    skipPlaybackTailWait = true;
                    rounds++;
                    continue;
                }
                if (smartPrerecord) {
                    log.warn("[知识库录音] 智能预录自动恢复仍失败 uuid={} user={}，继续监听",
                            uuid, userText.length() > 20 ? userText.substring(0, 20) + "…" : userText);
                    dialogTurnRegistry.aiYieldsFloor(uuid);
                    rounds++;
                    continue;
                }
            }

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
                    boolean asyncPlayback = Boolean.TRUE.equals(resp.getPlaybackWaitHandled())
                            && recordingOnlyPlaybackService.isOutboundPlaybackAsync();
                    boolean bargeIn = false;
                    if (!asyncPlayback) {
                        bargeIn = Boolean.TRUE.equals(resp.getPlaybackWaitHandled())
                                ? false
                                : waitPlaybackWithBargeIn(uuid, played);
                        callSessionRecordService.syncAsrBaselineAfterPlayback(uuid);
                    }
                    if (bargeIn) {
                        dialogTurnRegistry.userTakesFloor(uuid, "turn-based-barge-in");
                    } else if (!asyncPlayback) {
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
                String ending = ForcedHangupRules.resolvePoliteEndWords(
                        StringUtils.hasText(resp.getEndWords()) ? resp.getEndWords() : resp.getReply());
                playEndingThenHangup(uuid, callRecordId, ending, "rule-hangup");
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

    private static String dialogLoopExitReason(String uuid, int rounds, boolean endedByHangup) {
        if (endedByHangup) {
            return "ai_hangup";
        }
        if (rounds <= 1) {
            return "early_customer_hangup";
        }
        if (rounds <= 3) {
            return "customer_hangup_mid_call";
        }
        return "customer_hangup_late";
    }

    private record AsrListenResult(String text, boolean speechWithoutRecognition, long listenMs, long asrMs) {}

    private AsrListenResult recordAndRecognize(String uuid) throws Exception {
        if (aiVoiceProperties.isAsrVadEnabled()) {
            try {
                long listenStart = System.currentTimeMillis();
                Path wav = callUtteranceRecordService.recordUntilSilence(uuid);
                long afterListen = System.currentTimeMillis();
                long size = Files.size(wav);
                log.info("[对话] 录音完成 uuid={} bytes={} listenMs={}", uuid, size, afterListen - listenStart);
                long asrStart = System.currentTimeMillis();
                String text = asrRecognitionService.recognize(wav);
                long asrMs = System.currentTimeMillis() - asrStart;
                dialogTurnRegistry.markListenPhaseMs(uuid, afterListen - listenStart);
                dialogTurnRegistry.markAsrPhaseMs(uuid, asrMs);
                if (StringUtils.hasText(text)) {
                    return new AsrListenResult(text, false, afterListen - listenStart, asrMs);
                }
                log.warn("[ASR] 有录音但未识别 uuid={} bytes={} asrMs={}", uuid, size, asrMs);
                return new AsrListenResult("", size >= MIN_SPEECH_WAV_BYTES, afterListen - listenStart, asrMs);
            } catch (NoSpeechDetectedException e) {
                log.debug("[对话] 未检测到客户说话 uuid={}，继续监听", uuid);
                return new AsrListenResult("", false, 0, 0);
            } catch (Exception e) {
                if (!NoSpeechDetectedException.isNoSpeech(e)) {
                    log.warn("[对话] VAD 录音失败 uuid={}: {}", uuid, e.getMessage());
                }
                return new AsrListenResult("", false, 0, 0);
            }
        }
        if (aiVoiceProperties.isAsrStreamEnabled() && !"sentence".equalsIgnoreCase(aiVoiceProperties.getAsrMode())) {
            return new AsrListenResult(recordStreamAsr(uuid), false, 0, 0);
        }
        return new AsrListenResult(recognizeChunk(uuid, aiVoiceProperties.getDialogRecordChunkSec()), false, 0, 0);
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
        if (voiceRuntimeSettingsService.isSmartPrerecordMode()) {
            if (!playRecordingOnly(uuid, callRecordId, text)) {
                log.warn("[知识库录音] 追问无可用录音 uuid={} text={}", uuid, text);
            }
        } else {
            callAiVoiceService.playText(uuid, text);
        }
        waitPlaybackWithBargeIn(uuid, text);
        callSessionRecordService.syncAsrBaselineAfterPlayback(uuid);
        appendHistory(history, "assistant", text);
        dialogTurnRegistry.aiYieldsFloor(uuid);
        return true;
    }

    private void playFarewellAndHangup(String uuid, Integer callRecordId,
                                       List<AiChatMessage> history, String goodbye) {
        appendHistory(history, "assistant", goodbye);
        playEndingThenHangup(uuid, callRecordId, goodbye, "farewell");
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
        if (voiceRuntimeSettingsService.isSmartPrerecordMode()) {
            if (!playRecordingOnly(uuid, callRecordId, opening)) {
                log.warn("[知识库录音] 开场白无可用录音 uuid={} recordId={}", uuid, callRecordId);
            }
        } else {
            callAiVoiceService.playText(uuid, opening);
        }
        waitPlaybackWithBargeIn(uuid, opening);
        callSessionRecordService.syncAsrBaselineAfterPlayback(uuid);
        appendHistory(history, "assistant", opening);
    }

    private static final String LOOP_END_WORDS =
            "好的，今天先聊到这儿，有需要随时联系我们，祝您生活愉快，再见。";

    private void playGracefulLoopEnd(String uuid, Integer callRecordId, List<AiChatMessage> history) {
        appendHistory(history, "assistant", LOOP_END_WORDS);
        playEndingThenHangup(uuid, callRecordId, LOOP_END_WORDS, "loop-end");
    }

    /** 客户长时间静默时，重播当前主线问题而非直接挂机 */
    private boolean tryReplayMainFlowQuestion(String uuid, Integer callRecordId, List<AiChatMessage> history) {
        try {
            int kbId = DialogCallContextService.DEFAULT_KB_ID;
            if (callRecordId != null) {
                var ctx = dialogCallContextService.resolve(callRecordId);
                if (ctx.hasKb()) {
                    kbId = ctx.getKbId();
                }
            }
            if (!dialogMainFlowService.isEnabled(kbId)) {
                return false;
            }
            String line = dialogMainFlowService.resumeAfterFallback(callRecordId);
            if (!StringUtils.hasText(line)) {
                return false;
            }
            if (playTurnBasedNudge(uuid, callRecordId, history, line)) {
                callDialogPersistService.appendAssistant(callRecordId, line);
                appendHistory(history, "assistant", line);
                log.info("[静默] 重播主线 step={} uuid={}", dialogMainFlowService.currentStep(callRecordId), uuid);
                return true;
            }
        } catch (Exception e) {
            log.warn("[静默] 重播主线失败 uuid={}: {}", uuid, e.getMessage());
        }
        return false;
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
        HangupDecision dec = forcedHangupService.evaluateBeforeAi(callRecordId, null, null, null);
        String goodbye = StringUtils.hasText(dec.getEndWords())
                ? dec.getEndWords() : ForcedHangupRules.DURATION_END_WORDS;
        appendHistory(history, "assistant", goodbye);
        playEndingThenHangup(uuid, callRecordId, goodbye, "duration-timeout");
    }

    private void playEndingThenHangup(String uuid, Integer callRecordId, String endText, String reason) {
        String ending = ForcedHangupRules.resolvePoliteEndWords(endText);
        politeEndingPlayed.set(true);
        if (voiceRuntimeSettingsService.isSmartPrerecordMode()) {
            try {
                if (playRecordingOnly(uuid, callRecordId, ending)) {
                    awaitOutboundPlaybackBeforeHangup(uuid);
                } else {
                    ttsFailureRecoveryService.playEndingThenHangup(uuid, callRecordId, ending, reason);
                    return;
                }
            } catch (Exception e) {
                log.warn("[知识库录音] 结束语播放失败 uuid={} reason={}: {}", uuid, reason, e.getMessage());
                ttsFailureRecoveryService.playEndingThenHangup(uuid, callRecordId, ending, reason);
                return;
            }
            if (eslService.uuidExists(uuid)) {
                eslService.hangupChannel(uuid, reason);
            }
            return;
        }
        ttsFailureRecoveryService.playEndingThenHangup(uuid, callRecordId, ending, reason);
    }

    private void awaitOutboundPlaybackBeforeHangup(String uuid) {
        try {
            recordingOnlyPlaybackService.awaitOutboundPlaybackReady(uuid);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private boolean playRecordingOnly(String uuid, Integer callRecordId, String text) throws Exception {
        int kbId = resolveKbId(callRecordId);
        if (recordingOnlyPlaybackService.playCachedPhrase(uuid, text, kbId)) {
            return true;
        }
        return recordingOnlyPlaybackService.playEnding(uuid, callRecordId, text);
    }

    private int resolveKbId(Integer callRecordId) {
        if (callRecordId == null) {
            return DialogCallContextService.DEFAULT_KB_ID;
        }
        var ctx = dialogCallContextService.resolve(callRecordId);
        return ctx.hasKb() ? ctx.getKbId() : DialogCallContextService.DEFAULT_KB_ID;
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

    private static boolean isLikelyEchoFromAssistant(String userText, List<AiChatMessage> history) {
        if (!StringUtils.hasText(userText) || history == null || history.isEmpty()) {
            return false;
        }
        int checked = 0;
        for (int i = history.size() - 1; i >= 0 && checked < 5; i--) {
            AiChatMessage m = history.get(i);
            if (!"assistant".equalsIgnoreCase(m.getRole()) || !StringUtils.hasText(m.getContent())) {
                continue;
            }
            checked++;
            if (ForcedHangupRules.isLikelyAsrEcho(userText, m.getContent())) {
                return true;
            }
        }
        return false;
    }

    private void awaitMinGapAfterAiSpeech(long lastAiSpeechMs) throws InterruptedException {
        int minGap = Math.max(80, aiVoiceProperties.resolveTurnBasedPlaybackTailMs() * 2);
        long sinceAi = System.currentTimeMillis() - lastAiSpeechMs;
        if (sinceAi < minGap) {
            Thread.sleep(minGap - sinceAi);
        }
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
        CallSessionMeta meta = callSessionMetaService.get(callRecordId);
        if (meta != null && StringUtils.hasText(meta.getCustomerPhone())) {
            return meta.getCustomerPhone();
        }
        CallRecord record = callRecordMapper.selectById(callRecordId);
        return record != null ? record.getCustomerPhone() : null;
    }
}
