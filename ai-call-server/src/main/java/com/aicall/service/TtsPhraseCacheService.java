package com.aicall.service;

import com.aicall.common.ForcedHangupRules;
import com.aicall.config.AiVoiceProperties;
import com.aicall.util.TtsAudioCacheKeyUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 相同话术 TTS 结果缓存，降低 CosyVoice 调用频率、缓解 428 限流。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TtsPhraseCacheService {

    private final AiVoiceProperties aiVoiceProperties;
    private final DashScopeVoiceTtsService dashScopeVoiceTtsService;
    private final AsrWarmupService asrWarmupService;
    private final DialogScriptPackRegistry dialogScriptPackRegistry;
    private final VoiceRuntimeSettingsService voiceRuntimeSettingsService;

    private final Map<String, Path> cache = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Path> eldest) {
            return size() > maxMemoryEntries();
        }
    };

    private volatile boolean commonPhrasesWarmed;
    private volatile boolean phraseWarmComplete;

    private final ConcurrentHashMap<String, CompletableFuture<Path>> inFlight = new ConcurrentHashMap<>();

    /** 启动后后台预热静默追问/没听清等高频话术，降低首通 TTS 延迟 */
    @EventListener(ContextRefreshedEvent.class)
    public void warmCommonPhrasesOnStartup() {
        if (!aiVoiceProperties.isTtsPhraseWarmOnStartup()) {
            phraseWarmComplete = true;
            return;
        }
        if (voiceRuntimeSettingsService.isSmartPrerecordMode()) {
            log.info("[TTS短语缓存] 智能预录模式，跳过 CosyVoice 话术预热");
            phraseWarmComplete = true;
            return;
        }
        if (commonPhrasesWarmed || !isAvailable()) {
            phraseWarmComplete = true;
            return;
        }
        commonPhrasesWarmed = true;
        List<String> basePhrases = List.of(
                "喂？",
                "喂？在听吗？",
                ForcedHangupRules.turnBasedSilenceProbe(1),
                ForcedHangupRules.turnBasedSilenceProbe(2),
                ForcedHangupRules.turnBasedSilenceProbe(3),
                ForcedHangupRules.turnBasedUnclearAsrNudge(),
                ForcedHangupRules.hearingFallbackReply(),
                ForcedHangupRules.llmTimeoutFallbackReply(),
                ForcedHangupRules.softDeclineRecoveryReply(),
                ForcedHangupRules.continueDialogReply(""),
                "您好，请问您这边可以听到吗？",
                "您好，请问是否可以正常沟通？",
                "不好意思，这边信号可能有点不好，您那边声音有点小，麻烦您再说一遍好吗？"
        );
        CompletableFuture.runAsync(() -> {
            waitForAsrWarmup();
            List<String> phrases = new java.util.ArrayList<>(basePhrases);
            int maxChars = Math.max(24, aiVoiceProperties.getMaxSpeakChars());
            for (String script : dialogScriptPackRegistry.distinctMainFlowScripts(
                    DialogCallContextService.DEFAULT_KB_ID)) {
                if (script.length() <= maxChars + 8 && !phrases.contains(script)) {
                    phrases.add(script);
                }
            }
            long warmDelayMs = aiVoiceProperties.isOutboundReadyGateEnabled()
                    ? 0
                    : Math.max(0, aiVoiceProperties.getOpeningVoicePrecacheDelayMs());
            if (warmDelayMs > 0) {
                try {
                    Thread.sleep(warmDelayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    phraseWarmComplete = true;
                    return;
                }
            }
            try {
                int warmed = 0;
                for (String phrase : phrases) {
                    try {
                        Path tmp = cacheDir().resolve("warm_" + phrase.hashCode() + ".wav");
                        if (synthesizeFixedPhraseToFile(tmp, phrase, null) != null) {
                            warmed++;
                        }
                    } catch (Exception e) {
                        log.debug("[TTS缓存] 预热跳过 phrase={}: {}", phrase, e.getMessage());
                    }
                }
                log.info("[TTS缓存] 常用话术预热完成 count={}/{}", warmed, phrases.size());
            } finally {
                phraseWarmComplete = true;
            }
        }, Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "tts-phrase-warm");
            t.setDaemon(true);
            return t;
        }));
    }

    public boolean isAvailable() {
        return dashScopeVoiceTtsService.isAvailable();
    }

    public boolean isPhraseWarmComplete() {
        return phraseWarmComplete;
    }

    private void waitForAsrWarmup() {
        if (!aiVoiceProperties.isAsrWarmOnStartup()) {
            return;
        }
        long deadline = System.currentTimeMillis() + Math.max(30_000L, aiVoiceProperties.getOutboundReadyMaxWaitMs());
        while (!asrWarmupService.isWarmComplete() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    public Path synthesizeToFile(Path out, String text) {
        return synthesizeToFile(out, text, null);
    }

    public Path synthesizeToFile(Path out, String text, String voiceId) {
        return synthesizeInternal(out, text, voiceId, false);
    }

    /** 开场白/结束语预合成：不带 Instruct，减少 428 */
    public Path synthesizeFixedPhraseToFile(Path out, String text, String voiceId) {
        return synthesizeInternal(out, text, voiceId, true);
    }

    /** 仅查缓存 wav，不触发合成 */
    public Optional<Path> findCachedWav(String text, String voiceId) {
        if (!StringUtils.hasText(text) || !aiVoiceProperties.isTtsPhraseCacheEnabled()) {
            return Optional.empty();
        }
        return findStoredWav(cacheKey(text, voiceId, false));
    }

    public Optional<Path> findCachedFixedPhraseWav(String text, String voiceId) {
        if (!StringUtils.hasText(text) || !aiVoiceProperties.isTtsPhraseCacheEnabled()) {
            return Optional.empty();
        }
        return findStoredWav(cacheKey(text, voiceId, true));
    }

    private Path synthesizeInternal(Path out, String text, String voiceId, boolean fixedPhrase) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        if (aiVoiceProperties.isTtsPhraseCacheEnabled()) {
            String key = cacheKey(text, voiceId, fixedPhrase);
            Optional<Path> hit = findStoredWav(key);
            if (hit.isPresent()) {
                try {
                    Files.copy(hit.get(), out, StandardCopyOption.REPLACE_EXISTING);
                    log.info("[TTS缓存] 命中 key={} bytes={}", key.substring(0, 8), Files.size(out));
                    return out;
                } catch (Exception e) {
                    synchronized (cache) {
                        cache.remove(key);
                    }
                }
            }
            return synthesizeWithDedup(key, out, text, voiceId, fixedPhrase);
        }
        if (!isAvailable()) {
            return null;
        }
        return fixedPhrase
                ? dashScopeVoiceTtsService.synthesizeFixedPhraseToFile(out, text, voiceId)
                : dashScopeVoiceTtsService.synthesizeToFile(out, text, voiceId);
    }

    private Path synthesizeWithDedup(String key, Path out, String text, String voiceId, boolean fixedPhrase) {
        CompletableFuture<Path> future = inFlight.computeIfAbsent(key, k -> CompletableFuture.supplyAsync(
                () -> doSynthesizeAndStore(k, text, voiceId, fixedPhrase), TTS_DEDUP_EXECUTOR));
        try {
            Path store = future.get();
            if (store == null || !Files.exists(store) || fileSize(store) <= 44) {
                return null;
            }
            Files.copy(store, out, StandardCopyOption.REPLACE_EXISTING);
            return out;
        } catch (Exception e) {
            inFlight.remove(key, future);
            log.debug("[TTS缓存] 合成失败 key={}: {}", key.substring(0, 8), e.getMessage());
            return null;
        }
    }

    private Path doSynthesizeAndStore(String key, String text, String voiceId, boolean fixedPhrase) {
        try {
            log.debug("[TTS缓存] 未命中 key={}，调用 CosyVoice 合成", key.substring(0, 8));
            if (!isAvailable()) {
                return null;
            }
            Path store = cacheDir().resolve(key + ".wav");
            try {
                Files.createDirectories(store.getParent());
            } catch (Exception e) {
                log.debug("[TTS缓存] 创建目录失败: {}", e.getMessage());
                return null;
            }
            Path built = fixedPhrase
                    ? dashScopeVoiceTtsService.synthesizeFixedPhraseToFile(store, text, voiceId)
                    : dashScopeVoiceTtsService.synthesizeToFile(store, text, voiceId);
            if (built == null || !Files.exists(built) || fileSize(built) <= 44) {
                return null;
            }
            synchronized (cache) {
                cache.put(key, store);
            }
            return store;
        } finally {
            inFlight.remove(key);
        }
    }

    private static final ExecutorService TTS_DEDUP_EXECUTOR = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "tts-phrase-dedup");
        t.setDaemon(true);
        return t;
    });

    private Path cacheDir() {
        String dir = aiVoiceProperties.getTtsPhraseCacheDir();
        if (!StringUtils.hasText(dir)) {
            dir = "./uploads/tts/phrases";
        }
        return Path.of(dir.replace('/', '\\'));
    }

    private Optional<Path> findStoredWav(String key) {
        Path disk = cacheDir().resolve(key + ".wav");
        if (fileSize(disk) > 44) {
            synchronized (cache) {
                cache.put(key, disk);
            }
            return Optional.of(disk);
        }
        synchronized (cache) {
            Path hit = cache.get(key);
            if (hit != null && fileSize(hit) > 44) {
                return Optional.of(hit);
            }
        }
        return Optional.empty();
    }

    private int maxMemoryEntries() {
        return Math.max(128, aiVoiceProperties.getTtsPhraseCacheMaxEntries());
    }

    private String cacheKey(String text, String voiceId, boolean fixedPhrase) {
        String voice = StringUtils.hasText(voiceId)
                ? dashScopeVoiceTtsService.voiceCacheSignature(voiceId)
                : dashScopeVoiceTtsService.voiceCacheSignature();
        String normalized = fixedPhrase
                ? text.trim()
                : TtsAudioCacheKeyUtil.normalizeReplyText(text, aiVoiceProperties.getMaxSpeakChars());
        if (!StringUtils.hasText(normalized)) {
            normalized = text.trim();
        }
        String raw = voice + "|" + normalized;
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            return HexFormat.of().formatHex(md.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return String.valueOf(raw.hashCode());
        }
    }

    private static long fileSize(Path p) {
        try {
            return Files.size(p);
        } catch (Exception e) {
            return 0;
        }
    }
}
