package com.aicall.service;

import com.aicall.common.TtsSynthesisException;
import com.aicall.common.DialogSlotHelper;
import com.aicall.common.ForcedHangupRules;
import com.aicall.config.AiVoiceProperties;
import com.aicall.config.FreeSwitchProperties;
import com.aicall.util.OralScriptNormalizer;
import com.aicall.util.SpeakTextLimiter;
import com.aicall.util.StreamTtsRemainder;
import com.aicall.dto.AiChatMessage;
import com.aicall.dto.AiChatRequest;
import com.aicall.dto.AiChatResponse;
import com.aicall.util.DialogTranscriptLog;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.concurrent.TimeoutException;

/**
 * AI 话术 → 语音播放（委托 VoicePlaybackService）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CallAiVoiceService {

    private static final Executor STREAM_TTS_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "stream-tts");
        t.setDaemon(true);
        return t;
    });

    private static final Executor REPLY_CACHE_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "reply-cache-store");
        t.setDaemon(true);
        return t;
    });

    private final AiVoiceProperties aiVoiceProperties;
    private final FreeSwitchProperties freeSwitchProperties;
    private final OllamaChatService ollamaChatService;
    private final VoicePlaybackService voicePlaybackService;
    private final FreeSwitchEslService eslService;
    private final DialogLlmExecutorService dialogLlmExecutorService;
    private final OpeningPlaybackService openingPlaybackService;
    private final OutboundDialogRegistry outboundDialogRegistry;
    private final OpeningPlaybackGuard openingPlaybackGuard;
    private final VoiceRuntimeSettingsService voiceRuntimeSettingsService;
    private final DialogTurnRegistry dialogTurnRegistry;
    private final TtsProsodyService ttsProsodyService;
    private final DialogReplyAudioCacheService dialogReplyAudioCacheService;
    private final com.aicall.service.prerecord.PrerecordCircuitService prerecordCircuitService;

    public void onCallAnswered(String fsUuid, Integer callRecordId) {
        if (!aiVoiceProperties.isEnabled() || !voiceRuntimeSettingsService.isPlayOpeningOnAnswer()) {
            return;
        }
        if (!StringUtils.hasText(fsUuid) || !freeSwitchProperties.isEnabled()) {
            return;
        }
        if (openingPlaybackGuard.hasPlayed(fsUuid)) {
            log.debug("[AI开场白] 已由对话循环播过，跳过 uuid={}", fsUuid);
            return;
        }
        try {
            openingPlaybackService.playOpening(fsUuid, callRecordId);
        } catch (Exception e) {
            log.warn("接通播报失败 uuid={}: {}", fsUuid, e.getMessage());
        }
    }

    public AiChatResponse voiceTurn(AiChatRequest req) {
        try {
            // 外呼通话：始终带超时走独立线程池，避免 dialog-loop 被 LLM 长时间阻塞导致「说完就卡住」
            if (req.getCallRecordId() != null || aiVoiceProperties.isDialogLlmAsync()) {
                return dialogLlmExecutorService.run(() -> voiceTurnInternal(req));
            }
            return voiceTurnInternal(req);
        } catch (TtsSynthesisException e) {
            throw e;
        } catch (TimeoutException e) {
            log.warn("[LLM] 大模型超时 uuid={} 播放兜底话术", req.getFsUuid());
            return voiceTurnWithLiveFallback(req);
        } catch (Exception e) {
            log.warn("[LLM] 大模型失败 uuid={}: {}", req.getFsUuid(), e.getMessage());
            return voiceTurnWithLiveFallback(req);
        }
    }

    private AiChatResponse voiceTurnWithLiveFallback(AiChatRequest req) {
        try {
            AiChatResponse fallback = ollamaChatService.buildLiveCallSlotResponse(req);
            fallback.setStreamedTtsPlayed(false);
            String reply = fallback.getReply();
            if (StringUtils.hasText(reply)) {
                DialogTranscriptLog.aiReply(req.getCallRecordId(), req.getFsUuid(), reply, "fallback", false);
            } else {
                log.warn("[LLM] 兜底应答为空 uuid={} recordId={}", req.getFsUuid(), req.getCallRecordId());
            }
            if (aiVoiceProperties.isEnabled() && StringUtils.hasText(req.getFsUuid())
                    && eslService.uuidExists(req.getFsUuid()) && StringUtils.hasText(reply)) {
                playText(req.getFsUuid(), reply);
            }
            return fallback;
        } catch (TtsSynthesisException e) {
            throw e;
        } catch (Exception ex) {
            log.warn("[LLM] 槽位兜底也失败 uuid={}: {}", req.getFsUuid(), ex.getMessage());
            AiChatResponse r = new AiChatResponse();
            String sorry = ForcedHangupRules.llmTimeoutFallbackReply();
            r.setReply(sorry);
            r.setShouldHangup(false);
            r.setStreamedTtsPlayed(false);
            DialogTranscriptLog.aiReply(req.getCallRecordId(), req.getFsUuid(), sorry, "fallback-timeout", false);
            if (aiVoiceProperties.isEnabled() && StringUtils.hasText(req.getFsUuid())
                    && eslService.uuidExists(req.getFsUuid())) {
                playText(req.getFsUuid(), sorry);
            }
            return r;
        }
    }

    private AiChatResponse voiceTurnInternal(AiChatRequest req) throws Exception {
        if (Boolean.TRUE.equals(req.getFirstTurn())) {
            AiChatResponse r = ollamaChatService.chat(req);
            DialogTranscriptLog.opening(req.getCallRecordId(), req.getFsUuid(), r.getReply());
            if (aiVoiceProperties.isEnabled() && StringUtils.hasText(req.getFsUuid())
                    && shouldPlayOnChannel(req.getFsUuid())) {
                playText(req.getFsUuid(), r.getReply());
            }
            return r;
        }
        DialogTranscriptLog.userSpeechToText(req.getCallRecordId(), req.getFsUuid(), req.getUserText());
        ttsProsodyService.bindForUserUtterance(req.getUserText());
        try {
            return voiceTurnDialog(req);
        } finally {
            ttsProsodyService.clear();
        }
    }

    private AiChatResponse voiceTurnDialog(AiChatRequest req) throws Exception {
        long turnStart = System.currentTimeMillis();
        Optional<AiChatResponse> cachedReply = tryReplyAudioCache(req, turnStart);
        if (cachedReply.isPresent()) {
            return cachedReply.get();
        }
        AtomicBoolean streamTtsStarted = new AtomicBoolean(false);
        AtomicReference<String> streamTtsPrefix = new AtomicReference<>("");
        AtomicReference<CompletableFuture<Void>> streamFirstPlayFuture = new AtomicReference<>();
        Consumer<String> onSentence = null;
        if (aiVoiceProperties.isDialogLlmStream() && aiVoiceProperties.isDialogLlmStreamTts()
                && req.getCallRecordId() != null && shouldPlayOnChannel(req.getFsUuid())) {
            onSentence = sentence -> {
                if (!StringUtils.hasText(sentence) || !streamTtsStarted.compareAndSet(false, true)) {
                    return;
                }
                String chunk = SpeakTextLimiter.limit(sentence.trim(), aiVoiceProperties.getMaxSpeakChars());
                streamTtsPrefix.set(chunk);
                log.info("[对话TTS] 流式首句开播 uuid={} 距回合开始{}ms len={}",
                        req.getFsUuid(), System.currentTimeMillis() - turnStart, chunk.length());
                String fsUuid = req.getFsUuid();
                streamFirstPlayFuture.set(CompletableFuture.runAsync(() -> playText(fsUuid, chunk), STREAM_TTS_EXECUTOR));
            };
        }
        AiChatResponse r = ollamaChatService.chat(req, onSentence);
        String toPlay = Boolean.TRUE.equals(r.getShouldHangup()) && StringUtils.hasText(r.getEndWords())
                ? r.getEndWords() : r.getReply();
        toPlay = dedupeAgainstLastAssistant(toPlay, req);
        if (StringUtils.hasText(toPlay)) {
            DialogTranscriptLog.aiReply(req.getCallRecordId(), req.getFsUuid(), toPlay, r.getModel(), r.getShouldHangup());
        } else {
            log.warn("[LLM] 应答为空 uuid={} recordId={} model={}",
                    req.getFsUuid(), req.getCallRecordId(), r.getModel());
        }
        r.setStreamedTtsPlayed(streamTtsStarted.get());
        if (aiVoiceProperties.isEnabled() && StringUtils.hasText(req.getFsUuid())) {
            if (shouldPlayOnChannel(req.getFsUuid())) {
                if (Boolean.TRUE.equals(r.getShouldHangup()) && StringUtils.hasText(r.getEndWords())) {
                    playFixedEnding(req.getFsUuid(), req.getCallRecordId(), r.getEndWords());
                } else if (streamTtsStarted.get()) {
                    playStreamTtsTail(req.getFsUuid(), toPlay, streamTtsPrefix.get(), streamFirstPlayFuture.get());
                    r.setPlaybackWaitHandled(true);
                } else {
                    playText(req.getFsUuid(), toPlay);
                }
                storeReplyAudioCache(req, toPlay, r);
                log.info("[对话播报] uuid={} 整段就绪 距本轮回话开始{}ms streamTts={}",
                        req.getFsUuid(), System.currentTimeMillis() - turnStart, streamTtsStarted.get());
            } else {
                log.warn("通道已不存在或已取消，跳过播报 uuid={}", req.getFsUuid());
            }
        }
        return r;
    }

    private Optional<AiChatResponse> tryReplyAudioCache(AiChatRequest req, long turnStart) {
        if (!dialogReplyAudioCacheService.isEnabled()
                || aiVoiceProperties.isDialogLlmStreamTts()
                || !StringUtils.hasText(req.getUserText())) {
            return Optional.empty();
        }
        Optional<DialogReplyAudioCacheService.CachedReply> hit =
                dialogReplyAudioCacheService.lookup(req.getUserText(), null);
        if (hit.isEmpty()) {
            log.debug("[问答缓存] 未命中 uuid={} question={}，走 LLM+TTS",
                    req.getFsUuid(), abbreviateUser(req.getUserText()));
            return Optional.empty();
        }
        if (!isValidReplyCacheWav(hit.get().wavPath())) {
            log.warn("[问答缓存] wav 无效，回退 LLM+TTS uuid={} question={}",
                    req.getFsUuid(), abbreviateUser(req.getUserText()));
            return Optional.empty();
        }
        return Optional.of(buildCachedReplyResponse(req, hit.get(), turnStart));
    }

    private static boolean isValidReplyCacheWav(Path wav) {
        if (wav == null || !Files.exists(wav)) {
            return false;
        }
        try {
            return Files.size(wav) > 44;
        } catch (Exception e) {
            return false;
        }
    }

    private static String abbreviateUser(String text) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        String s = text.trim();
        return s.length() <= 20 ? s : s.substring(0, 20) + "…";
    }

    private AiChatResponse buildCachedReplyResponse(AiChatRequest req,
                                                    DialogReplyAudioCacheService.CachedReply hit,
                                                    long turnStart) {
        AiChatResponse r = new AiChatResponse();
        r.setReply(hit.replyText());
        r.setModel("reply-audio-cache");
        r.setFromReplyAudioCache(true);
        r.setLatencyMs(System.currentTimeMillis() - turnStart);
        DialogTranscriptLog.aiReply(req.getCallRecordId(), req.getFsUuid(), hit.replyText(),
                r.getModel(), false);
        if (aiVoiceProperties.isEnabled() && StringUtils.hasText(req.getFsUuid())
                && shouldPlayOnChannel(req.getFsUuid())) {
            boolean ok = voicePlaybackService.playSynthesizedWav(req.getFsUuid(), hit.wavPath());
            if (!ok) {
                log.info("[问答缓存] wav 播放失败，回退实时 TTS 合成 uuid={}", req.getFsUuid());
                playText(req.getFsUuid(), hit.replyText());
            } else {
                try {
                    voicePlaybackService.waitPlaybackFinished(req.getFsUuid(), hit.replyText(), null);
                } catch (Exception e) {
                    log.debug("[问答缓存] 等待播完 uuid={}: {}", req.getFsUuid(), e.getMessage());
                }
            }
            r.setPlaybackWaitHandled(true);
            log.info("[问答缓存] 已播报 uuid={} 距回合开始{}ms", req.getFsUuid(),
                    System.currentTimeMillis() - turnStart);
        }
        return r;
    }

    private void storeReplyAudioCache(AiChatRequest req, String replyText, AiChatResponse r) {
        if (!dialogReplyAudioCacheService.isEnabled()
                || Boolean.TRUE.equals(r.getFromReplyAudioCache())
                || Boolean.TRUE.equals(r.getShouldHangup())
                || Boolean.TRUE.equals(r.getStreamedTtsPlayed())
                || !StringUtils.hasText(req.getUserText())
                || !StringUtils.hasText(replyText)) {
            return;
        }
        String userText = req.getUserText();
        CompletableFuture.runAsync(
                () -> dialogReplyAudioCacheService.store(userText, replyText, null),
                REPLY_CACHE_EXECUTOR);
    }

    private boolean shouldPlayOnChannel(String fsUuid) {
        if (!freeSwitchProperties.isEnabled() || !eslService.uuidExists(fsUuid)) {
            return false;
        }
        return outboundDialogRegistry.shouldContinue(fsUuid);
    }

    private String dedupeAgainstLastAssistant(String reply, AiChatRequest req) {
        if (!StringUtils.hasText(reply)) {
            return reply;
        }
        String lastAi = lastAssistantFromHistory(req.getHistory());
        if (!StringUtils.hasText(lastAi)) {
            return reply;
        }
        if (!ForcedHangupRules.isLikelyAsrEcho(reply, lastAi) && !reply.trim().equals(lastAi.trim())) {
            return reply;
        }
        DialogSlotHelper.Slots slots = DialogSlotHelper.extract(req.getHistory(), req.getUserText());
        String alt = DialogSlotHelper.diversifyIfNeeded(reply, slots, req.getUserText(), req.getHistory());
        return StringUtils.hasText(alt) ? alt : reply;
    }

    private static String lastAssistantFromHistory(List<AiChatMessage> history) {
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

    /** 首句流式已播：等首句结束再分段补播剩余，并在本方法内等待全部播完 */
    private void playStreamTtsTail(String fsUuid, String fullReply, String streamedPrefix,
                                   CompletableFuture<Void> firstPlayFuture) throws Exception {
        if (firstPlayFuture != null) {
            try {
                firstPlayFuture.join();
            } catch (CompletionException e) {
                TtsSynthesisException tts = TtsSynthesisException.unwrap(e);
                if (tts != null) {
                    throw tts;
                }
                throw e;
            }
        }
        String prefix = streamedPrefix != null ? streamedPrefix.trim() : "";
        String remainder = StreamTtsRemainder.unplayed(fullReply, prefix);
        CompletableFuture<Path> remainderWavFuture = null;
        if (StringUtils.hasText(remainder) && shouldPlayOnChannel(fsUuid)) {
            String rem = remainder;
            remainderWavFuture = CompletableFuture.supplyAsync(() -> {
                try {
                    return voicePlaybackService.synthesizeToFile(fsUuid, rem);
                } catch (Exception e) {
                    if (e instanceof TtsSynthesisException tts) {
                        throw new CompletionException(tts);
                    }
                    throw new CompletionException(e);
                }
            }, STREAM_TTS_EXECUTOR);
        }
        if (StringUtils.hasText(prefix)) {
            log.info("[对话TTS] 等待流式首句播完 uuid={} len={}", fsUuid, prefix.length());
            voicePlaybackService.waitPlaybackFinished(fsUuid, prefix, null);
        }
        if (!StringUtils.hasText(remainder)) {
            if (remainderWavFuture != null) {
                remainderWavFuture.cancel(true);
            }
            log.info("[对话TTS] 流式首句已覆盖全文 uuid={}", fsUuid);
            return;
        }
        log.info("[对话TTS] 流式补播 uuid={} 剩余len={}", fsUuid, remainder.length());
        if (!shouldPlayOnChannel(fsUuid)) {
            if (remainderWavFuture != null) {
                remainderWavFuture.cancel(true);
            }
            return;
        }
        boolean played = false;
        if (remainderWavFuture != null) {
            try {
                Path wav = remainderWavFuture.join();
                played = voicePlaybackService.playSynthesizedWav(fsUuid, wav);
            } catch (CompletionException e) {
                TtsSynthesisException tts = TtsSynthesisException.unwrap(e);
                if (tts != null) {
                    throw tts;
                }
                log.warn("[对话TTS] 并行预合成失败，回退实时合成 uuid={}: {}", fsUuid, e.getMessage());
            }
        }
        if (!played) {
            playText(fsUuid, remainder);
        }
        voicePlaybackService.waitPlaybackFinished(fsUuid, remainder, null);
    }

    /** 固定结束语：仅播预录音，不调 TTS */
    public void playFixedEnding(String fsUuid, Integer callRecordId, String endText) {
        if (!StringUtils.hasText(fsUuid) || !StringUtils.hasText(endText)) {
            return;
        }
        if (!freeSwitchProperties.isEnabled() || !shouldPlayOnChannel(fsUuid)) {
            return;
        }
        try {
            if (!openingPlaybackService.playEnding(fsUuid, callRecordId, endText)) {
                playText(fsUuid, endText);
            }
        } catch (Exception e) {
            log.warn("结束语播放失败 uuid={}: {}", fsUuid, e.getMessage());
            playText(fsUuid, endText);
        }
    }

    /** 通话结束：释放播放锁 */
    public void releaseCallResources(String fsUuid) {
        if (!StringUtils.hasText(fsUuid)) {
            return;
        }
        outboundDialogRegistry.cancel(fsUuid);
        openingPlaybackGuard.clear(fsUuid);
        dialogTurnRegistry.unregister(fsUuid);
        voicePlaybackService.releaseChannel(fsUuid);
    }

    public void playText(String fsUuid, String text) {
        if (!StringUtils.hasText(fsUuid) || !StringUtils.hasText(text)) {
            return;
        }
        if (!freeSwitchProperties.isEnabled()) {
            log.debug("[语音桩] uuid={} text={}", fsUuid, text.length() > 80 ? text.substring(0, 80) : text);
            return;
        }
        if (!shouldPlayOnChannel(fsUuid)) {
            log.warn("播报跳过：通道不存在或对话已取消 uuid={}", fsUuid);
            return;
        }
        String safe = SpeakTextLimiter.limit(text, aiVoiceProperties.getMaxSpeakChars());
        safe = OralScriptNormalizer.normalize(safe);
        boolean ok = aiVoiceProperties.isTtsSentenceSequentialEnabled()
                ? voicePlaybackService.playTextSequential(fsUuid, safe)
                : aiVoiceProperties.isTtsStreamEnabled()
                ? voicePlaybackService.playTextStreaming(fsUuid, safe)
                : voicePlaybackService.playOnChannel(fsUuid, safe);
        if (!ok) {
            if (!eslService.uuidExists(fsUuid)) {
                log.warn("播报失败：通话通道已结束（客户挂机或 FS 超时）uuid={}", fsUuid);
                return;
            }
            prerecordCircuitService.recordTtsFailure(fsUuid);
            throw new TtsSynthesisException("所有播报方式均失败", false);
        }
    }
}
