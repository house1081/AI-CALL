package com.aicall.service;

import com.aicall.dto.AiChatMessage;
import com.aicall.dto.AiChatRequest;
import com.aicall.dto.AiChatResponse;
import com.aicall.dto.AsrBrowserResult;
import com.aicall.dto.DialogVoiceTrainStartResponse;
import com.aicall.dto.DialogVoiceTrainTurnResponse;
import com.aicall.entity.AiPrompt;
import com.aicall.util.DialogTranscriptLog;
import com.aicall.util.UploadedAudioConverter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** 管理端对话训练：语音+文字输入，模式由外呼配置决定（预录匹配 / 大模型实时） */
@Slf4j
@Service
@RequiredArgsConstructor
public class DialogVoiceTrainingService {

    private static final long SESSION_IDLE_MS = 45L * 60L * 1000L;

    private static final class VoiceTrainSession {
        private final String sessionId;
        private final int simCallRecordId;
        private final int kbId;
        private final long startMs;
        private volatile long lastTouchMs;
        private final List<AiChatMessage> history = new ArrayList<>();
        private boolean businessProbeThisTurn;
        private int turnNo;
        private int completedTurns;

        VoiceTrainSession(String sessionId, int simCallRecordId, int kbId, long startMs) {
            this.sessionId = sessionId;
            this.simCallRecordId = simCallRecordId;
            this.kbId = kbId;
            this.startMs = startMs;
            this.lastTouchMs = startMs;
        }

        void touch() {
            lastTouchMs = System.currentTimeMillis();
        }

        long lastTouchMs() {
            return lastTouchMs;
        }

        String sessionId() {
            return sessionId;
        }

        int simCallRecordId() {
            return simCallRecordId;
        }

        int kbId() {
            return kbId;
        }

        long startMs() {
            return startMs;
        }

        List<AiChatMessage> history() {
            return history;
        }

        boolean businessProbeThisTurn() {
            return businessProbeThisTurn;
        }

        void setBusinessProbeThisTurn(boolean businessProbeThisTurn) {
            this.businessProbeThisTurn = businessProbeThisTurn;
        }

        int nextTurnNo() {
            return ++turnNo;
        }

        int completedTurns() {
            return completedTurns;
        }

        void markTurnCompleted() {
            completedTurns++;
        }
    }

    private final DialogMainFlowService dialogMainFlowService;
    private final OpeningPlaybackService openingPlaybackService;
    private final AiPromptRecordingService aiPromptRecordingService;
    private final VoiceRuntimeSettingsService voiceRuntimeSettingsService;
    private final KbRecordingOutboundService kbRecordingOutboundService;
    private final AsrRecognitionService asrRecognitionService;
    private final OpeningVoiceCacheService openingVoiceCacheService;
    private final OllamaChatService ollamaChatService;
    private final DialogVoiceTrainRecordService dialogVoiceTrainRecordService;
    private final CallDialogPersistService callDialogPersistService;

    private final Map<String, VoiceTrainSession> sessions = new ConcurrentHashMap<>();
    private final AtomicInteger simRecordSeq = new AtomicInteger(-1000);

    public DialogVoiceTrainStartResponse start(Integer kbId) {
        int effectiveKb = kbId != null ? kbId : DialogCallContextService.DEFAULT_KB_ID;
        String sessionId = UUID.randomUUID().toString().replace("-", "");
        int simCallRecordId = simRecordSeq.decrementAndGet();
        dialogMainFlowService.initCall(simCallRecordId, effectiveKb);

        VoiceTrainSession session = new VoiceTrainSession(sessionId, simCallRecordId, effectiveKb, System.currentTimeMillis());
        sessions.put(sessionId, session);

        boolean smartPrerecord = voiceRuntimeSettingsService.isSmartPrerecordMode();
        Map<String, Object> modelCfg = ollamaChatService.configInfo();
        String opening;
        Long openingLatencyMs = null;

        if (smartPrerecord) {
            opening = openingPlaybackService.resolveOpeningText();
        } else {
            AiChatRequest req = new AiChatRequest();
            req.setFirstTurn(true);
            req.setTrainSessionId(sessionId);
            req.setCallRecordId(simCallRecordId);
            long t0 = System.currentTimeMillis();
            AiChatResponse chat = ollamaChatService.chat(req);
            opening = chat.getReply();
            openingLatencyMs = chat.getLatencyMs() != null ? chat.getLatencyMs() : System.currentTimeMillis() - t0;
        }

        String openingAudioUrl = resolveOpeningAudioUrl();

        String mode = smartPrerecord ? "smart_prerecord" : "ai_realtime";
        String provider = modelCfg.get("provider") != null ? modelCfg.get("provider").toString() : null;
        String modelName = modelCfg.get("model") != null ? modelCfg.get("model").toString() : null;

        dialogVoiceTrainRecordService.logSessionStart(
                sessionId, effectiveKb, simCallRecordId, mode, provider, modelName, opening, openingAudioUrl);
        callDialogPersistService.bindCall(simCallRecordId);
        if (StringUtils.hasText(opening)) {
            appendHistory(session, "assistant", opening);
            callDialogPersistService.appendAssistant(simCallRecordId, opening);
            DialogTranscriptLog.opening(simCallRecordId, sessionId, opening);
        }

        log.info("[对话训练] 开始 session={} kb={} simRecordId={} mode={}",
                sessionId, effectiveKb, simCallRecordId, mode);

        DialogVoiceTrainStartResponse resp = new DialogVoiceTrainStartResponse();
        resp.setSessionId(sessionId);
        resp.setSimCallRecordId(simCallRecordId);
        resp.setKbId(effectiveKb);
        resp.setMode(mode);
        resp.setProvider(provider);
        resp.setModel(modelName);
        resp.setOpeningText(opening);
        resp.setOpeningAudioUrl(openingAudioUrl);
        resp.setOpeningLatencyMs(openingLatencyMs);
        return resp;
    }

    public DialogVoiceTrainTurnResponse voiceTurn(String sessionId, MultipartFile audio, Boolean businessProbeThisTurn)
            throws Exception {
        VoiceTrainSession session = requireSession(sessionId);
        applyBusinessProbe(session, businessProbeThisTurn);
        int turnNo = session.nextTurnNo();
        long asrStart = System.currentTimeMillis();
        SavedTrainAudio saved = saveUploadAsBrowserAsrWav(session, turnNo, audio);
        AsrBrowserResult asr = asrRecognitionService.recognizeBrowserMicResult(saved.wavPath());
        long asrMs = System.currentTimeMillis() - asrStart;
        String audioRel = dialogVoiceTrainRecordService.toRelativePath(saved.wavPath());
        if (!asr.hasText()) {
            dialogVoiceTrainRecordService.logEmptyAsr(sessionId, turnNo, audioRel, asrMs,
                    asr.failureCode() != null ? asr.failureCode() : "asr_no_text");
            DialogVoiceTrainTurnResponse empty = new DialogVoiceTrainTurnResponse();
            empty.setSessionId(sessionId);
            empty.setUserText("");
            empty.setAsrMs(asrMs);
            empty.setAsrError(asr.failureCode() != null ? asr.failureCode() : "no_text");
            empty.setHandled(false);
            return empty;
        }
        DialogVoiceTrainTurnResponse resp = processTextTurn(session, asr.text(), asrMs, turnNo, audioRel);
        resp.setAsrMs(asrMs);
        return resp;
    }

    public DialogVoiceTrainTurnResponse textTurn(String sessionId, String userText, Boolean businessProbeThisTurn)
            throws Exception {
        if (!StringUtils.hasText(sessionId)) {
            throw new IllegalArgumentException("sessionId 不能为空");
        }
        if (!StringUtils.hasText(userText)) {
            throw new IllegalArgumentException("请输入客户话术");
        }
        VoiceTrainSession session = requireSession(sessionId);
        applyBusinessProbe(session, businessProbeThisTurn);
        int turnNo = session.nextTurnNo();
        return processTextTurn(session, userText, 0, turnNo, null);
    }

    public void endSession(String sessionId) {
        endSession(sessionId, "manual_hangup");
    }

    public void endSession(String sessionId, String reason) {
        VoiceTrainSession session = sessions.remove(sessionId);
        if (session != null) {
            long durationMs = System.currentTimeMillis() - session.startMs();
            dialogVoiceTrainRecordService.logSessionEnd(
                    sessionId, reason, session.completedTurns(), durationMs);
            callDialogPersistService.unbindCall(session.simCallRecordId());
            dialogMainFlowService.clearCall(session.simCallRecordId());
            log.info("[对话训练] 结束 session={} reason={} turns={} durationMs={}",
                    sessionId, reason, session.completedTurns(), durationMs);
        }
    }

    public List<Map<String, Object>> listRecentRecords(int limit) {
        return dialogVoiceTrainRecordService.listRecentSessions(limit);
    }

    public List<Map<String, Object>> loadRecordEvents(String sessionId) {
        return dialogVoiceTrainRecordService.loadSessionEvents(sessionId);
    }

    private DialogVoiceTrainTurnResponse processTextTurn(VoiceTrainSession session, String userText, long asrMs,
                                                         int turnNo, String userAudioRel) throws Exception {
        DialogVoiceTrainTurnResponse resp;
        if (voiceRuntimeSettingsService.isSmartPrerecordMode()) {
            resp = processPrerecordTurn(session, userText, asrMs);
        } else {
            resp = processLlmTurn(session, userText, asrMs);
        }
        persistTurnRecord(session, turnNo, userText, userAudioRel, asrMs, resp);
        if (resp.isShouldHangup()) {
            endSession(session.sessionId(), "ai_hangup");
        }
        return resp;
    }

    private void persistTurnRecord(VoiceTrainSession session, int turnNo, String userText, String userAudioRel,
                                   long asrMs, DialogVoiceTrainTurnResponse resp) {
        String trimmedUser = StringUtils.hasText(userText) ? userText.trim() : "";
        if (StringUtils.hasText(trimmedUser)) {
            DialogTranscriptLog.userSpeechToText(session.simCallRecordId(), session.sessionId(), trimmedUser);
            callDialogPersistService.appendUser(session.simCallRecordId(), trimmedUser);
        }
        if (StringUtils.hasText(resp.getReplyText())) {
            DialogTranscriptLog.aiReply(session.simCallRecordId(), session.sessionId(),
                    resp.getReplyText(), resp.getModel(), resp.isShouldHangup());
            callDialogPersistService.appendAssistant(session.simCallRecordId(), resp.getReplyText());
        }
        dialogVoiceTrainRecordService.logTurn(
                session.sessionId(),
                turnNo,
                trimmedUser,
                userAudioRel,
                asrMs,
                new DialogVoiceTrainRecordService.DialogVoiceTrainTurnSnapshot(
                        resp.getReplyText(),
                        resp.getReplyAudioUrl(),
                        resp.getModel(),
                        resp.isHandled(),
                        resp.isShouldHangup(),
                        resp.getHangupType(),
                        resp.getBusinessProbeNext(),
                        resp.getInvalidChatRounds(),
                        resp.getElapsedSeconds(),
                        resp.getTurnMs()));
        session.markTurnCompleted();
    }

    private DialogVoiceTrainTurnResponse processPrerecordTurn(VoiceTrainSession session, String userText, long asrMs)
            throws Exception {
        long turnStart = System.currentTimeMillis();
        var turn = kbRecordingOutboundService.handleTrainingTurn(
                session.sessionId(), session.simCallRecordId(), userText, session.kbId());

        DialogVoiceTrainTurnResponse resp = new DialogVoiceTrainTurnResponse();
        resp.setSessionId(session.sessionId());
        resp.setUserText(StringUtils.hasText(userText) ? userText.trim() : "");
        resp.setReplyText(turn.getReplyText());
        resp.setReplyAudioUrl(turn.getReplyAudioUrl());
        resp.setModel(turn.getModel());
        resp.setHandled(turn.isHandled());
        resp.setShouldHangup(turn.isShouldHangup());
        resp.setAsrMs(asrMs);
        resp.setTurnMs(System.currentTimeMillis() - turnStart);
        String trimmed = StringUtils.hasText(userText) ? userText.trim() : "";
        if (StringUtils.hasText(trimmed)) {
            appendHistory(session, "user", trimmed);
        }
        if (StringUtils.hasText(turn.getReplyText())) {
            appendHistory(session, "assistant", turn.getReplyText());
        }
        return resp;
    }

    private DialogVoiceTrainTurnResponse processLlmTurn(VoiceTrainSession session, String userText, long asrMs) {
        long turnStart = System.currentTimeMillis();
        String trimmed = StringUtils.hasText(userText) ? userText.trim() : "";

        AiChatRequest req = new AiChatRequest();
        req.setUserText(trimmed);
        req.setHistory(new ArrayList<>(session.history()));
        req.setTrainSessionId(session.sessionId());
        req.setCallRecordId(session.simCallRecordId());
        req.setBusinessProbeThisTurn(session.businessProbeThisTurn());

        AiChatResponse chat = ollamaChatService.chat(req);
        if (StringUtils.hasText(trimmed)) {
            appendHistory(session, "user", trimmed);
        }
        appendHistory(session, "assistant", chat.getReply());
        session.setBusinessProbeThisTurn(Boolean.TRUE.equals(chat.getBusinessProbeNext()));

        DialogVoiceTrainTurnResponse resp = new DialogVoiceTrainTurnResponse();
        resp.setSessionId(session.sessionId());
        resp.setUserText(trimmed);
        resp.setReplyText(chat.getReply());
        resp.setModel(chat.getModel());
        resp.setHandled(true);
        resp.setShouldHangup(Boolean.TRUE.equals(chat.getShouldHangup()));
        resp.setHangupType(chat.getHangupType());
        resp.setBusinessProbeNext(chat.getBusinessProbeNext());
        resp.setInvalidChatRounds(chat.getInvalidChatRounds());
        resp.setElapsedSeconds(chat.getElapsedSeconds());
        resp.setAsrMs(asrMs);
        resp.setTurnMs(chat.getLatencyMs() != null ? chat.getLatencyMs() : System.currentTimeMillis() - turnStart);
        return resp;
    }

    private record SavedTrainAudio(Path rawPath, Path wavPath) {}

    private static void appendHistory(VoiceTrainSession session, String role, String content) {
        if (!StringUtils.hasText(content)) {
            return;
        }
        AiChatMessage msg = new AiChatMessage();
        msg.setRole(role);
        msg.setContent(content.trim());
        session.history().add(msg);
    }

    private static void applyBusinessProbe(VoiceTrainSession session, Boolean businessProbeThisTurn) {
        if (businessProbeThisTurn != null) {
            session.setBusinessProbeThisTurn(businessProbeThisTurn);
        }
    }

    private VoiceTrainSession requireSession(String sessionId) {
        if (!StringUtils.hasText(sessionId) || !sessionId.matches("[a-fA-F0-9]{20,64}")) {
            throw new IllegalArgumentException("训练会话无效，请重新拨打");
        }
        VoiceTrainSession session = sessions.get(sessionId);
        if (session == null) {
            throw new IllegalArgumentException("训练会话不存在或已结束，请重新拨打");
        }
        session.touch();
        return session;
    }

    @Scheduled(fixedDelay = 300_000)
    public void expireIdleSessions() {
        long now = System.currentTimeMillis();
        List<String> expired = new ArrayList<>();
        for (var e : sessions.entrySet()) {
            if (now - e.getValue().lastTouchMs() > SESSION_IDLE_MS) {
                expired.add(e.getKey());
            }
        }
        for (String sessionId : expired) {
            endSession(sessionId, "idle_timeout");
        }
    }

    private SavedTrainAudio saveUploadAsBrowserAsrWav(VoiceTrainSession session, int turnNo, MultipartFile audio)
            throws IOException {
        if (audio == null || audio.isEmpty()) {
            throw new IllegalArgumentException("请上传语音");
        }
        String original = audio.getOriginalFilename() != null ? audio.getOriginalFilename() : "voice.webm";
        Path dir = dialogVoiceTrainRecordService.sessionDir(session.sessionId());
        long ts = System.currentTimeMillis();
        String prefix = "turn_" + String.format("%03d", turnNo) + "_" + ts;
        Path raw = dir.resolve(prefix + suffixOf(original));
        try (InputStream in = audio.getInputStream()) {
            Files.copy(in, raw, StandardCopyOption.REPLACE_EXISTING);
        }
        Path wav = dir.resolve(prefix + "_asr.wav");
        if (UploadedAudioConverter.isSupportedUploadName(original)
                || original.toLowerCase().endsWith(".webm")
                || original.toLowerCase().endsWith(".ogg")) {
            UploadedAudioConverter.convertToBrowserAsrWav(raw, wav);
            return new SavedTrainAudio(raw, wav);
        }
        throw new IOException("不支持的音频格式，请使用 wav/mp3/webm");
    }

    private static String suffixOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(dot) : ".webm";
    }

    private String resolveOpeningAudioUrl() {
        if (voiceRuntimeSettingsService.isSmartPrerecordMode()) {
            AiPrompt prompt = aiPromptRecordingService.loadActivePrompt();
            Path wav = aiPromptRecordingService.resolveOpeningWav(prompt);
            if (wav != null) {
                return RecordingOnlyPlaybackService.toPublicUrl(wav.toString());
            }
            return null;
        }
        Path cached = openingVoiceCacheService.copyToCallPlayback("voice-train-opening");
        if (cached != null) {
            return RecordingOnlyPlaybackService.toPublicUrl(cached.toString());
        }
        return null;
    }
}
