package com.aicall.service;

import com.aicall.common.TtsSynthesisException;
import com.aicall.common.ForcedHangupRules;
import com.aicall.config.AiVoiceProperties;
import com.aicall.entity.AiPrompt;
import com.aicall.mapper.AiPromptMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 固定结束语预合成：按 CosyVoice 音色分目录缓存，挂断时按当前音色拷贝播放。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EndingVoiceCacheService {

    private static final String LOOP_END_WORDS =
            "好的，今天先这样，有需要随时找我们，祝您生活愉快，再见。";

    private static final String KEY_TTS_FAILURE = "tts_fail";

    private static final String KEY_DEFAULT = "default";
    private static final String KEY_DURATION = "duration";
    private static final String KEY_LOOP = "loop";

    private static final List<String> ALL_ENDING_KEYS = List.of(
            KEY_DEFAULT, KEY_DURATION, KEY_LOOP, KEY_TTS_FAILURE);

    private final AiVoiceProperties aiVoiceProperties;
    private final TtsPhraseCacheService ttsPhraseCacheService;
    private final DashScopeVoiceTtsService dashScopeVoiceTtsService;
    private final LocalPromptWavService localPromptWavService;
    private final AiPromptMapper aiPromptMapper;
    private final FixedPhraseVoiceCatalogService voiceCatalog;
    private final VoiceRuntimeSettingsService voiceRuntimeSettingsService;

    private final Map<String, String> textToKey = new LinkedHashMap<>();

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ending-voice-cache");
        t.setDaemon(true);
        return t;
    });

    @EventListener(ApplicationReadyEvent.class)
    void checkEndingCacheOnStartup() {
        if (!aiVoiceProperties.isOpeningVoicePrecacheEnabled()
                || !aiVoiceProperties.isOpeningVoicePrecacheOnStartup()) {
            return;
        }
        executor.submit(() -> {
            try {
                int delay = Math.max(0, aiVoiceProperties.getOpeningVoicePrecacheDelayMs());
                if (delay > 0) {
                    Thread.sleep(delay);
                }
                logStartupCheck();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.warn("[结束语缓存] 启动检查失败: {}", e.getMessage());
            }
        });
    }

    private void logStartupCheck() {
        if (voiceRuntimeSettingsService.isSmartPrerecordMode()) {
            return;
        }
        AiPrompt active = aiPromptMapper.selectOne(
                new LambdaQueryWrapper<AiPrompt>().eq(AiPrompt::getIsActive, 1).last("LIMIT 1"));
        if (active == null) {
            log.info("[结束语缓存] 启动检查：未配置使用中话术模板");
            return;
        }
        String voiceId = voiceCatalog.resolveActiveVoiceId();
        int ready = countReadyVoices();
        int total = voiceCatalog.listAllVoiceIds().size();
        if (isAnyReady()) {
            log.info("[结束语缓存] 启动检查：已就绪 voice={} {}/{} 音色",
                    maskVoice(voiceId), ready, total);
        } else {
            log.warn("[结束语缓存] 启动检查：未就绪 voice={} {}/{} 音色，请在管理端手动预生成（启动不自动合成）",
                    maskVoice(voiceId), ready, total);
        }
    }

    public void regenerateAsync(Integer promptId) {
        if (!aiVoiceProperties.isOpeningVoicePrecacheEnabled()) {
            return;
        }
        executor.submit(() -> {
            try {
                log.info("[结束语缓存] 触发全音色预合成 promptId={}", promptId);
                if (promptId != null) {
                    AiPrompt p = aiPromptMapper.selectById(promptId);
                    if (p != null && Integer.valueOf(1).equals(p.getIsActive())) {
                        regenerateAll(p);
                        return;
                    }
                }
                ensureActiveEndingCached(true);
            } catch (Exception e) {
                log.warn("[结束语缓存] 异步合成失败 promptId={}: {}", promptId, e.getMessage());
            }
        });
    }

    public void ensureActiveEndingCached(boolean force) {
        AiPrompt active = aiPromptMapper.selectOne(
                new LambdaQueryWrapper<AiPrompt>().eq(AiPrompt::getIsActive, 1).last("LIMIT 1"));
        if (active == null) {
            return;
        }
        if (!force && isFullyReady(active)) {
            return;
        }
        String voiceId = voiceCatalog.resolveActiveVoiceId();
        if (!StringUtils.hasText(voiceId)) {
            return;
        }
        log.info("[结束语缓存] 仅预合成当前音色 voice={}", maskVoice(voiceId));
        regenerateAllForVoice(active, voiceId);
    }

    /** 为所有已知音色预生成结束语（默认/超时/轮次） */
    public void regenerateAll(AiPrompt prompt) {
        List<String> voices = voiceCatalog.listAllVoiceIds();
        log.info("[结束语缓存] 开始全音色预合成 promptId={} 音色数={}", prompt != null ? prompt.getId() : null, voices.size());
        for (String voiceId : voices) {
            regenerateAllForVoice(prompt, voiceId);
        }
    }

    public void regenerateAllForVoice(AiPrompt prompt, String voiceId) {
        String defaultEnd = resolveDefaultEndText(prompt);
        rebuildTextIndex(defaultEnd);
        Map<String, String> keyTexts = endingKeyTexts(defaultEnd);
        for (String key : ALL_ENDING_KEYS) {
            if (!synthesizeIfNeeded(voiceId, key, keyTexts.get(key))) {
                return;
            }
        }
    }

    public Path copyToCallPlayback(String fsUuid, String endText) {
        if (!StringUtils.hasText(fsUuid) || !StringUtils.hasText(endText)) {
            return null;
        }
        String voiceId = voiceCatalog.resolveActiveVoiceId();
        Path src = resolveSourceForText(endText.trim(), voiceId);
        if (src == null) {
            log.warn("[结束语缓存] 无预录音 uuid={} voice={} text={}",
                    fsUuid, maskVoice(voiceId), truncate(endText, 32));
            return null;
        }
        try {
            Path target = callSharedPath(fsUuid, "ending");
            Files.createDirectories(target.getParent());
            Files.copy(src, target, StandardCopyOption.REPLACE_EXISTING);
            log.info("[结束语缓存] 已拷贝 uuid={} voice={} path={}", fsUuid, maskVoice(voiceId), target);
            return target;
        } catch (Exception e) {
            log.warn("[结束语缓存] 拷贝失败 uuid={}: {}", fsUuid, e.getMessage());
            return null;
        }
    }

    public boolean isAnyReady() {
        AiPrompt active = aiPromptMapper.selectOne(
                new LambdaQueryWrapper<AiPrompt>().eq(AiPrompt::getIsActive, 1).last("LIMIT 1"));
        return active != null && isFullyReady(active);
    }

    public boolean isReadyForVoice(String voiceId) {
        AiPrompt active = aiPromptMapper.selectOne(
                new LambdaQueryWrapper<AiPrompt>().eq(AiPrompt::getIsActive, 1).last("LIMIT 1"));
        if (active == null || !StringUtils.hasText(voiceId)) {
            return false;
        }
        return isFullyReadyForVoice(active, voiceId);
    }

    public int countReadyVoices() {
        AiPrompt active = aiPromptMapper.selectOne(
                new LambdaQueryWrapper<AiPrompt>().eq(AiPrompt::getIsActive, 1).last("LIMIT 1"));
        if (active == null) {
            return 0;
        }
        int n = 0;
        for (String voiceId : voiceCatalog.listAllVoiceIds()) {
            if (isFullyReadyForVoice(active, voiceId)) {
                n++;
            }
        }
        return n;
    }

    private boolean isFullyReady(AiPrompt prompt) {
        String voiceId = voiceCatalog.resolveActiveVoiceId();
        return StringUtils.hasText(voiceId) && isFullyReadyForVoice(prompt, voiceId);
    }

    private boolean isFullyReadyForVoice(AiPrompt prompt, String voiceId) {
        if (!StringUtils.hasText(voiceId)) {
            return false;
        }
        Map<String, String> keyTexts = endingKeyTexts(resolveDefaultEndText(prompt));
        for (String key : ALL_ENDING_KEYS) {
            if (!isKeyReady(voiceId, key, keyTexts.get(key))) {
                return false;
            }
        }
        return true;
    }

    private Map<String, String> endingKeyTexts(String defaultEnd) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(KEY_DEFAULT, defaultEnd);
        m.put(KEY_DURATION, ForcedHangupRules.DURATION_END_WORDS);
        m.put(KEY_LOOP, LOOP_END_WORDS);
        m.put(KEY_TTS_FAILURE, ForcedHangupRules.TTS_FAILURE_END_WORDS);
        return m;
    }

    private boolean isKeyReady(String voiceId, String key, String text) {
        if (!StringUtils.hasText(text)) {
            return true;
        }
        try {
            Path wav = voiceDir(voiceId).resolve("ending_" + key + ".wav");
            Path meta = voiceDir(voiceId).resolve("ending_" + key + ".meta");
            if (!Files.exists(wav) || !Files.exists(meta) || Files.size(wav) <= 44) {
                return false;
            }
            return cacheSignature(text, voiceId).equals(Files.readString(meta).trim());
        } catch (Exception e) {
            return false;
        }
    }

    /** @return false 表示遇到 428/429，后续预合成应中止 */
    private boolean synthesizeIfNeeded(String voiceId, String key, String text) {
        if (!StringUtils.hasText(text) || !StringUtils.hasText(voiceId)) {
            return true;
        }
        String hash = cacheSignature(text, voiceId);
        try {
            Path voiceDir = voiceDir(voiceId);
            Files.createDirectories(voiceDir);
            Path wav = voiceDir.resolve("ending_" + key + ".wav");
            Path meta = voiceDir.resolve("ending_" + key + ".meta");
            if (Files.exists(wav) && Files.exists(meta) && hash.equals(Files.readString(meta).trim())) {
                publishLegacyIfActive(voiceId, key, wav);
                return true;
            }
            long t0 = System.currentTimeMillis();
            Path tmp = voiceDir.resolve("ending_building_" + key + ".wav");
            Path out = synthesizeEndingAudio(tmp, text.trim(), voiceId, key);
            if (out == null || !Files.exists(out) || Files.size(out) <= 44) {
                log.warn("[结束语缓存] 合成无有效音频 key={} voice={}", key, maskVoice(voiceId));
                return true;
            }
            Files.move(out, wav, StandardCopyOption.REPLACE_EXISTING);
            Files.writeString(meta, hash, StandardCharsets.UTF_8);
            publishLegacyIfActive(voiceId, key, wav);
            log.info("[结束语缓存] 已预合成 key={} voice={} bytes={} 耗时{}ms",
                    key, maskVoice(voiceId), Files.size(wav), System.currentTimeMillis() - t0);
            return true;
        } catch (Exception e) {
            TtsSynthesisException tts = TtsSynthesisException.unwrap(e);
            if (tts != null && tts.isRateLimited()) {
                log.warn("[结束语缓存] CosyVoice 限流，中止剩余预合成 key={} voice={}", key, maskVoice(voiceId));
                return false;
            }
            log.warn("[结束语缓存] 合成异常 key={} voice={}: {}", key, maskVoice(voiceId), e.getMessage());
            return true;
        }
    }

    /** CosyVoice 优先；418/异常时回退 Windows SAPI，确保 tts_fail 等挂断语也有本地 wav */
    private Path synthesizeEndingAudio(Path out, String text, String voiceId, String key) {
        if (ttsPhraseCacheService.isAvailable()) {
            try {
                Path cosy = ttsPhraseCacheService.synthesizeFixedPhraseToFile(out, text, voiceId);
                if (cosy != null && Files.exists(cosy)) {
                    try {
                        if (Files.size(cosy) > 44) {
                            return cosy;
                        }
                    } catch (Exception ignored) {
                    }
                }
            } catch (Exception e) {
                TtsSynthesisException tts = TtsSynthesisException.unwrap(e);
                if (tts != null && tts.isRateLimited()) {
                    throw tts;
                }
                log.warn("[结束语缓存] CosyVoice 失败 key={} voice={}，尝试本地 SAPI: {}",
                        key, maskVoice(voiceId), e.getMessage());
            }
        }
        try {
            Path local = localPromptWavService.synthesizeLocalFallbackToFile(out, text);
            if (local != null && Files.exists(local)) {
                try {
                    if (Files.size(local) > 44) {
                        log.info("[结束语缓存] 已用本地 SAPI 预合成 key={} voice={}", key, maskVoice(voiceId));
                        return local;
                    }
                } catch (Exception ignored) {
                }
            }
        } catch (Exception e) {
            log.warn("[结束语缓存] 本地 SAPI 失败 key={} voice={}: {}", key, maskVoice(voiceId), e.getMessage());
        }
        return null;
    }

    private void publishLegacyIfActive(String voiceId, String key, Path localWav) throws Exception {
        if (!voiceId.equals(voiceCatalog.resolveActiveVoiceId())) {
            return;
        }
        Path shared = fsLegacySharedPath(key);
        Files.createDirectories(shared.getParent());
        Files.copy(localWav, shared, StandardCopyOption.REPLACE_EXISTING);
        Path legacyLocal = localCachePath().resolve("ending_" + key + ".wav");
        Files.createDirectories(legacyLocal.getParent());
        Files.copy(localWav, legacyLocal, StandardCopyOption.REPLACE_EXISTING);
    }

    private Path resolveSourceForText(String text, String voiceId) {
        String norm = normalizeText(text);
        String key = textToKey.get(norm);
        if (StringUtils.hasText(key)) {
            Path p = resolveSourceWav(voiceId, key);
            if (p != null) {
                return p;
            }
        }
        if (norm.contains(normalizeText(ForcedHangupRules.DURATION_END_WORDS))) {
            return resolveSourceWav(voiceId, KEY_DURATION);
        }
        if (norm.contains(normalizeText(LOOP_END_WORDS))) {
            return resolveSourceWav(voiceId, KEY_LOOP);
        }
        if (norm.contains(normalizeText(ForcedHangupRules.TTS_FAILURE_END_WORDS))) {
            return resolveSourceWav(voiceId, KEY_TTS_FAILURE);
        }
        return resolveSourceWav(voiceId, KEY_DEFAULT);
    }

    private Path resolveSourceWav(String voiceId, String key) {
        Path perVoice = voiceDir(voiceId).resolve("ending_" + key + ".wav");
        try {
            if (Files.exists(perVoice) && Files.size(perVoice) > 44) {
                return perVoice;
            }
        } catch (Exception ignored) {
        }
        if (voiceId.equals(voiceCatalog.resolveActiveVoiceId())) {
            for (Path p : List.of(fsLegacySharedPath(key), localCachePath().resolve("ending_" + key + ".wav"))) {
                try {
                    if (Files.exists(p) && Files.size(p) > 44) {
                        return p;
                    }
                } catch (Exception ignored) {
                }
            }
        }
        return null;
    }

    private void rebuildTextIndex(String defaultEnd) {
        textToKey.clear();
        putIndex(defaultEnd, KEY_DEFAULT);
        putIndex(ForcedHangupRules.END_WORDS, KEY_DEFAULT);
        putIndex(ForcedHangupRules.DURATION_END_WORDS, KEY_DURATION);
        putIndex(LOOP_END_WORDS, KEY_LOOP);
        putIndex(ForcedHangupRules.TTS_FAILURE_END_WORDS, KEY_TTS_FAILURE);
    }

    private void putIndex(String text, String key) {
        if (StringUtils.hasText(text)) {
            textToKey.put(normalizeText(text.trim()), key);
        }
    }

    private static String resolveDefaultEndText(AiPrompt prompt) {
        if (prompt != null && StringUtils.hasText(prompt.getEndRemarks())) {
            return prompt.getEndRemarks().trim();
        }
        return ForcedHangupRules.END_WORDS;
    }

    private Path voiceDir(String voiceId) {
        return localCachePath().resolve(FixedPhraseVoiceCatalogService.voiceKey(voiceId));
    }

    private Path localCachePath() {
        String dir = aiVoiceProperties.getEndingVoiceCacheDir();
        if (!StringUtils.hasText(dir)) {
            dir = "./uploads/tts/ending";
        }
        return Path.of(dir.replace('/', '\\'));
    }

    private Path fsLegacySharedPath(String key) {
        String dir = aiVoiceProperties.getFsSharedWavDir();
        if (!StringUtils.hasText(dir)) {
            dir = aiVoiceProperties.getFsTempWavDir();
        }
        return Path.of(dir.replace('/', '\\'), "ending_" + key + ".wav");
    }

    private Path callSharedPath(String fsUuid, String suffix) {
        String dir = aiVoiceProperties.getFsSharedWavDir();
        if (!StringUtils.hasText(dir)) {
            dir = aiVoiceProperties.getFsTempWavDir();
        }
        return Path.of(dir.replace('/', '\\'),
                "aicall_" + suffix + "_" + fsUuid.replace("-", "") + ".wav");
    }

    private String cacheSignature(String text, String voiceId) {
        return dashScopeVoiceTtsService.voiceCacheSignature(voiceId) + "|" + hashText(text);
    }

    private static String hashText(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] dig = md.digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(dig);
        } catch (Exception e) {
            return String.valueOf(text.hashCode());
        }
    }

    private static String normalizeText(String text) {
        return text.trim().replaceAll("[\\s，,。.!！?？~～]+", "");
    }

    private static String truncate(String s, int max) {
        if (s == null || s.length() <= max) {
            return s;
        }
        return s.substring(0, max) + "…";
    }

    private static String maskVoice(String voiceId) {
        if (!StringUtils.hasText(voiceId)) {
            return "(未配置)";
        }
        return voiceId.length() <= 20 ? voiceId : voiceId.substring(0, 12) + "…";
    }
}
