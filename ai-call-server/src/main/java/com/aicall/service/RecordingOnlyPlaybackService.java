package com.aicall.service;

import com.aicall.config.AiVoiceProperties;
import com.aicall.entity.DialogTrainingQa;
import com.aicall.service.DialogTurnRegistry.ActiveSpeaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;

/**
 * 仅播放已有录音（知识库 wav / 固定话术上传），不进行 CosyVoice 实时合成。
 */
@Slf4j
@Service
public class RecordingOnlyPlaybackService {

    private final VoicePlaybackService voicePlaybackService;
    private final TtsPhraseCacheService ttsPhraseCacheService;
    private final FixedPhrasePlaybackService fixedPhrasePlaybackService;
    private final OpeningVoiceCacheService openingVoiceCacheService;
    private final VoiceRuntimeSettingsService voiceRuntimeSettingsService;
    private final DialogTrainingQaService dialogTrainingQaService;
    private final AiVoiceProperties aiVoiceProperties;
    private final CallUtteranceRecordService callUtteranceRecordService;
    private final DialogTurnRegistry dialogTurnRegistry;
    private final FreeSwitchEslService eslService;
    private final CallSessionRecordService callSessionRecordService;

    private final ExecutorService playbackWatchExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "prerecord-playback-watch");
        t.setDaemon(true);
        return t;
    });
    private final ConcurrentHashMap<String, CompletableFuture<Void>> pendingWatches = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> asyncPlayStartedAt = new ConcurrentHashMap<>();

    public RecordingOnlyPlaybackService(VoicePlaybackService voicePlaybackService,
                                        TtsPhraseCacheService ttsPhraseCacheService,
                                        FixedPhrasePlaybackService fixedPhrasePlaybackService,
                                        OpeningVoiceCacheService openingVoiceCacheService,
                                        VoiceRuntimeSettingsService voiceRuntimeSettingsService,
                                        @Lazy DialogTrainingQaService dialogTrainingQaService,
                                        AiVoiceProperties aiVoiceProperties,
                                        CallUtteranceRecordService callUtteranceRecordService,
                                        DialogTurnRegistry dialogTurnRegistry,
                                        FreeSwitchEslService eslService,
                                        CallSessionRecordService callSessionRecordService) {
        this.voicePlaybackService = voicePlaybackService;
        this.ttsPhraseCacheService = ttsPhraseCacheService;
        this.fixedPhrasePlaybackService = fixedPhrasePlaybackService;
        this.openingVoiceCacheService = openingVoiceCacheService;
        this.voiceRuntimeSettingsService = voiceRuntimeSettingsService;
        this.dialogTrainingQaService = dialogTrainingQaService;
        this.aiVoiceProperties = aiVoiceProperties;
        this.callUtteranceRecordService = callUtteranceRecordService;
        this.dialogTurnRegistry = dialogTurnRegistry;
        this.eslService = eslService;
        this.callSessionRecordService = callSessionRecordService;
    }

    public boolean playWavPath(String uuid, String wavPath, String waitText) throws Exception {
        return playWavPath(uuid, wavPath, waitText, !shouldAsyncPlayback());
    }

    /**
     * @param waitForCompletion true=阻塞至播完（或插嘴）；false=仅下发 FS 播放，后台监听
     */
    public boolean playWavPath(String uuid, String wavPath, String waitText, boolean waitForCompletion)
            throws Exception {
        Path wav = resolveExistingWav(wavPath);
        if (wav == null) {
            return false;
        }
        boolean ok = voicePlaybackService.playSynthesizedWav(uuid, wav);
        if (ok && StringUtils.hasText(waitText)) {
            if (waitForCompletion) {
                waitPlaybackWithOptionalBargeIn(uuid, waitText);
            } else {
                schedulePlaybackWatch(uuid, waitText);
            }
        }
        return ok;
    }

    public boolean playCachedPhrase(String uuid, String text) throws Exception {
        return playCachedPhrase(uuid, text, null);
    }

    /**
     * 播放话术录音：智能预录仅查知识库上传 wav；AI 实时可走 CosyVoice 短语缓存。
     */
    public boolean playCachedPhrase(String uuid, String text, Integer kbId) throws Exception {
        if (!StringUtils.hasText(uuid) || !StringUtils.hasText(text)) {
            return false;
        }
        if (voiceRuntimeSettingsService.isSmartPrerecordMode()) {
            return playKbAnswerWav(uuid, text, kbId);
        }
        Optional<Path> cached = ttsPhraseCacheService.findCachedWav(text.trim(), null);
        if (cached.isEmpty()) {
            log.debug("[纯录音] 话术缓存未命中 text={}", abbreviate(text));
            return false;
        }
        boolean ok = voicePlaybackService.playSynthesizedWav(uuid, cached.get());
        if (ok) {
            if (shouldAsyncPlayback()) {
                schedulePlaybackWatch(uuid, text.trim());
            } else {
                waitPlaybackWithOptionalBargeIn(uuid, text.trim());
            }
        }
        return ok;
    }

    /** 外呼异步播完（智能预录 / 问答缓存等） */
    public boolean isOutboundPlaybackAsync() {
        return aiVoiceProperties.isPrerecordPlaybackAsync();
    }

    /**
     * 播放已有 wav；async 模式下不阻塞，由 {@link #awaitOutboundPlaybackReady} 在下一轮听音前等待。
     */
    public boolean playExistingWavPath(String uuid, Path wav, String waitText) throws Exception {
        if (wav == null) {
            return false;
        }
        boolean ok = voicePlaybackService.playSynthesizedWav(uuid, wav);
        if (ok && StringUtils.hasText(waitText)) {
            if (isOutboundPlaybackAsync()) {
                schedulePlaybackWatch(uuid, waitText);
            } else {
                waitPlaybackWithOptionalBargeIn(uuid, waitText);
            }
        }
        return ok;
    }

    /** 下一轮听音前：等待异步播完；若用户已插嘴则立即返回 */
    public void awaitOutboundPlaybackReady(String uuid) throws InterruptedException {
        if (!isOutboundPlaybackAsync() || !StringUtils.hasText(uuid)) {
            return;
        }
        if (dialogTurnRegistry.getSpeaker(uuid) == ActiveSpeaker.USER) {
            cancelPlaybackWatch(uuid);
            return;
        }
        long deadline = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < deadline) {
            if (dialogTurnRegistry.getSpeaker(uuid) == ActiveSpeaker.USER) {
                cancelPlaybackWatch(uuid);
                return;
            }
            CompletableFuture<Void> pending = pendingWatches.get(uuid.trim());
            if ((pending == null || pending.isDone()) && !voicePlaybackService.isPlaybackActive(uuid)) {
                Long started = asyncPlayStartedAt.get(uuid.trim());
                if (started != null && System.currentTimeMillis() - started < 350) {
                    Thread.sleep(25);
                    continue;
                }
                asyncPlayStartedAt.remove(uuid.trim());
                return;
            }
            Thread.sleep(25);
        }
        log.warn("[纯录音] 等待播完超时 uuid={}", uuid);
    }

    public void cancelPlaybackWatch(String uuid) {
        if (!StringUtils.hasText(uuid)) {
            return;
        }
        CompletableFuture<Void> pending = pendingWatches.remove(uuid.trim());
        if (pending != null && !pending.isDone()) {
            pending.cancel(true);
        }
    }

    public boolean shouldAsyncPlayback() {
        return isOutboundPlaybackAsync() && voiceRuntimeSettingsService.isSmartPrerecordMode();
    }

    private void schedulePlaybackWatch(String uuid, String waitText) {
        if (!StringUtils.hasText(uuid)) {
            return;
        }
        String id = uuid.trim();
        cancelPlaybackWatch(id);
        asyncPlayStartedAt.put(id, System.currentTimeMillis());
        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
            try {
                boolean bargeIn = waitPlaybackWithOptionalBargeInReturning(id, waitText);
                if (!bargeIn && eslService.uuidExists(id)) {
                    callSessionRecordService.syncAsrBaselineAfterPlayback(id);
                    if (dialogTurnRegistry.getSpeaker(id) == ActiveSpeaker.AI) {
                        dialogTurnRegistry.aiYieldsFloor(id);
                    }
                }
            } catch (Exception e) {
                log.warn("[纯录音] 异步播完监听异常 uuid={}: {}", id, e.getMessage());
            } finally {
                pendingWatches.remove(id);
                asyncPlayStartedAt.remove(id);
            }
        }, playbackWatchExecutor);
        pendingWatches.put(id, future);
        log.debug("[纯录音] 已启动异步播完监听 uuid={}", id);
    }

    private void waitPlaybackWithOptionalBargeIn(String uuid, String waitText) throws Exception {
        waitPlaybackWithOptionalBargeInReturning(uuid, waitText);
    }

    private boolean waitPlaybackWithOptionalBargeInReturning(String uuid, String waitText) throws Exception {
        BooleanSupplier bargePoll = null;
        if (aiVoiceProperties.isBargeInEnabled() && aiVoiceProperties.isBargeInDuringTurnBased()) {
            bargePoll = () -> {
                if (!eslService.uuidExists(uuid)) {
                    return true;
                }
                if (callUtteranceRecordService.detectBargeInFromSession(uuid)) {
                    dialogTurnRegistry.userTakesFloor(uuid, "playback-barge-in");
                    return true;
                }
                return false;
            };
        }
        return voicePlaybackService.waitPlaybackFinished(uuid, waitText, bargePoll);
    }

    private boolean playKbAnswerWav(String uuid, String text, Integer kbId) throws Exception {
        int effectiveKb = kbId != null ? kbId : DialogCallContextService.DEFAULT_KB_ID;
        DialogTrainingQa qa = dialogTrainingQaService.findByAnswerText(effectiveKb, text.trim());
        if (qa == null || !StringUtils.hasText(qa.getAnswerWavPath())) {
            log.debug("[纯录音] 知识库无上传录音 kb={} text={}", effectiveKb, abbreviate(text));
            return false;
        }
        log.info("[纯录音] 知识库话术 kb={} qaId={} text={}", effectiveKb, qa.getId(), abbreviate(text));
        return playWavPath(uuid, qa.getAnswerWavPath(), text);
    }

    public boolean playOpening(String uuid) throws Exception {
        if (fixedPhrasePlaybackService.playCachedOpening(uuid)) {
            return true;
        }
        if (voiceRuntimeSettingsService.isSmartPrerecordMode()) {
            return false;
        }
        Path prewarmed = openingVoiceCacheService.copyToCallPlayback(uuid);
        if (prewarmed != null) {
            return voicePlaybackService.playOutboundOpeningWav(uuid, prewarmed);
        }
        return false;
    }

    public boolean playEnding(String uuid, Integer callRecordId, String endText) throws Exception {
        return fixedPhrasePlaybackService.playCachedEnding(uuid, callRecordId, endText);
    }

    public Path resolveExistingWav(String wavPath) {
        if (!StringUtils.hasText(wavPath)) {
            return null;
        }
        Path p = Path.of(wavPath.replace('/', '\\'));
        try {
            return java.nio.file.Files.exists(p) && java.nio.file.Files.size(p) > 44 ? p : null;
        } catch (Exception e) {
            return null;
        }
    }

    public static String toPublicUrl(String wavPath) {
        if (!StringUtils.hasText(wavPath)) {
            return null;
        }
        String normalized = wavPath.replace('\\', '/');
        int idx = normalized.indexOf("/uploads/");
        if (idx >= 0) {
            return normalized.substring(idx);
        }
        if (normalized.startsWith("./uploads/")) {
            return normalized.substring(1);
        }
        if (normalized.startsWith("uploads/")) {
            return "/" + normalized;
        }
        return normalized.startsWith("/") ? normalized : "/" + normalized;
    }

    private static String abbreviate(String text) {
        String s = text.trim();
        return s.length() <= 24 ? s : s.substring(0, 24) + "…";
    }
}
