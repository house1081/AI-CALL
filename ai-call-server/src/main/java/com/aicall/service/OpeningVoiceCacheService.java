package com.aicall.service;

import com.aicall.config.AiVoiceProperties;
import com.aicall.entity.AiPrompt;
import com.aicall.mapper.AiPromptMapper;
import com.aicall.util.TelephonyWavUtil;
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
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 开场白预合成：按 CosyVoice 音色分目录缓存，接通后按当前音色拷贝播放。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OpeningVoiceCacheService {

    private static final String OPENING_FILE = "opening.wav";
    private static final String OPENING_META = "opening.meta";
    /** 兼容旧版单文件缓存 */
    private static final String LEGACY_ACTIVE_FILE = "opening_active.wav";
    private static final String LEGACY_ACTIVE_META = "opening_active.meta";

    private final AiVoiceProperties aiVoiceProperties;
    private final TtsPhraseCacheService ttsPhraseCacheService;
    private final DashScopeVoiceTtsService dashScopeVoiceTtsService;
    private final AiPromptMapper aiPromptMapper;
    private final FixedPhraseVoiceCatalogService voiceCatalog;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "opening-voice-cache");
        t.setDaemon(true);
        return t;
    });

    @EventListener(ApplicationReadyEvent.class)
    void warmOnApplicationReady() {
        if (!aiVoiceProperties.isOpeningVoicePrecacheEnabled()) {
            return;
        }
        executor.submit(() -> {
            try {
                int delay = Math.max(0, aiVoiceProperties.getOpeningVoicePrecacheDelayMs());
                if (delay > 0) {
                    Thread.sleep(delay);
                }
                if (isReady()) {
                    log.info("[开场白缓存] 当前音色已有预合成 wav voice={}", maskVoice(voiceCatalog.resolveActiveVoiceId()));
                    return;
                }
                log.warn("[开场白缓存] 无有效预录音，启动全音色预合成");
                ensureActiveOpeningCached(true);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.warn("[开场白缓存] 启动预热失败: {}", e.getMessage());
            }
        });
    }

    public void regenerateAsync(Integer promptId) {
        if (!aiVoiceProperties.isOpeningVoicePrecacheEnabled()) {
            return;
        }
        executor.submit(() -> {
            try {
                log.info("[开场白缓存] 触发全音色预合成 promptId={}", promptId);
                if (promptId != null) {
                    AiPrompt p = aiPromptMapper.selectById(promptId);
                    if (p != null && Integer.valueOf(1).equals(p.getIsActive())) {
                        regenerateAll(p);
                        return;
                    }
                }
                ensureActiveOpeningCached(true);
            } catch (Exception e) {
                log.warn("[开场白缓存] 异步合成失败 promptId={}: {}", promptId, e.getMessage());
            }
        });
    }

    /** 为所有已知音色预生成开场白 */
    public void regenerateAll(AiPrompt prompt) {
        if (prompt == null || !StringUtils.hasText(prompt.getOpeningRemarks())) {
            return;
        }
        List<String> voices = voiceCatalog.listAllVoiceIds();
        log.info("[开场白缓存] 开始全音色预合成 promptId={} 音色数={}", prompt.getId(), voices.size());
        for (String voiceId : voices) {
            regenerateForVoice(prompt, voiceId);
        }
    }

    public void regenerate(AiPrompt prompt) {
        regenerateAll(prompt);
    }

    public void regenerateForVoice(AiPrompt prompt, String voiceId) {
        if (prompt == null || !StringUtils.hasText(prompt.getOpeningRemarks()) || !StringUtils.hasText(voiceId)) {
            return;
        }
        String text = prompt.getOpeningRemarks().trim();
        String hash = cacheSignature(text, voiceId);
        try {
            Path voiceDir = voiceDir(voiceId);
            Files.createDirectories(voiceDir);
            Path wav = voiceDir.resolve(OPENING_FILE);
            Path meta = voiceDir.resolve(OPENING_META);

            if (Files.exists(wav) && Files.exists(meta) && hash.equals(Files.readString(meta).trim())) {
                if (isDurationValidForText(wav, text)) {
                    log.info("[开场白缓存] 内容未变，跳过 voice={} promptId={}", maskVoice(voiceId), prompt.getId());
                    if (voiceId.equals(voiceCatalog.resolveActiveVoiceId())) {
                        publishLegacyAlias(wav);
                    }
                    return;
                }
                log.warn("[开场白缓存] 哈希匹配但音频过短，重新合成 voice={} promptId={} bytes={}",
                        maskVoice(voiceId), prompt.getId(), Files.size(wav));
            }

            if (!ttsPhraseCacheService.isAvailable()) {
                log.warn("[开场白缓存] TTS 不可用，无法预合成 voice={}", maskVoice(voiceId));
                return;
            }
            long t0 = System.currentTimeMillis();
            Path tmp = voiceDir.resolve("opening_building.wav");
            Path out = ttsPhraseCacheService.synthesizeToFile(tmp, text, voiceId);
            if (out == null || !Files.exists(out) || Files.size(out) <= 44) {
                log.warn("[开场白缓存] 合成无有效音频 voice={} promptId={}", maskVoice(voiceId), prompt.getId());
                return;
            }
            Files.move(out, wav, StandardCopyOption.REPLACE_EXISTING);
            Files.writeString(meta, hash, StandardCharsets.UTF_8);
            if (voiceId.equals(voiceCatalog.resolveActiveVoiceId())) {
                publishLegacyAlias(wav);
            }
            log.info("[开场白缓存] 已预合成 voice={} promptId={} bytes={} 耗时{}ms",
                    maskVoice(voiceId), prompt.getId(), Files.size(wav), System.currentTimeMillis() - t0);
        } catch (Exception e) {
            log.warn("[开场白缓存] 合成异常 voice={} promptId={}: {}", maskVoice(voiceId), prompt.getId(), e.getMessage());
        }
    }

    public void ensureActiveOpeningCached(boolean force) {
        AiPrompt active = loadActivePrompt();
        if (active == null) {
            return;
        }
        if (!force && isReady()) {
            return;
        }
        regenerateAll(active);
    }

    public boolean isReady() {
        return isReadyForVoice(voiceCatalog.resolveActiveVoiceId());
    }

    public boolean isReadyForVoice(String voiceId) {
        AiPrompt active = loadActivePrompt();
        if (active == null || !StringUtils.hasText(active.getOpeningRemarks()) || !StringUtils.hasText(voiceId)) {
            return false;
        }
        return isValidCacheFor(active.getOpeningRemarks().trim(), voiceId);
    }

    public int countReadyVoices() {
        AiPrompt active = loadActivePrompt();
        if (active == null || !StringUtils.hasText(active.getOpeningRemarks())) {
            return 0;
        }
        String text = active.getOpeningRemarks().trim();
        int n = 0;
        for (String voiceId : voiceCatalog.listAllVoiceIds()) {
            if (isValidCacheFor(text, voiceId)) {
                n++;
            }
        }
        return n;
    }

    public boolean isValidCacheFor(String openingText, String voiceId) {
        if (!StringUtils.hasText(openingText) || !StringUtils.hasText(voiceId)) {
            return false;
        }
        Path wav = voiceDir(voiceId).resolve(OPENING_FILE);
        Path meta = voiceDir(voiceId).resolve(OPENING_META);
        try {
            if (Files.exists(wav) && Files.size(wav) > 44 && Files.exists(meta)) {
                if (!Files.readString(meta, StandardCharsets.UTF_8).trim()
                        .equals(cacheSignature(openingText.trim(), voiceId))) {
                    return false;
                }
                return isDurationValidForText(wav, openingText.trim());
            }
        } catch (Exception ignored) {
        }
        if (voiceId.equals(voiceCatalog.resolveActiveVoiceId())) {
            return isValidLegacyCache(openingText.trim());
        }
        return false;
    }

    public Path copyToCallPlayback(String fsUuid) {
        if (!StringUtils.hasText(fsUuid)) {
            return null;
        }
        String voiceId = voiceCatalog.resolveActiveVoiceId();
        Path src = resolveSourceWav(voiceId);
        if (src == null) {
            log.warn("[开场白缓存] 当前音色无预录音 voice={}", maskVoice(voiceId));
            regenerateAsync(null);
            return null;
        }
        try {
            Path target = callSharedPath(fsUuid);
            Files.createDirectories(target.getParent());
            Files.copy(src, target, StandardCopyOption.REPLACE_EXISTING);
            log.info("[开场白缓存] 已拷贝 uuid={} voice={} path={}", fsUuid, maskVoice(voiceId), target);
            return target;
        } catch (Exception e) {
            log.warn("[开场白缓存] 拷贝失败 uuid={}: {}", fsUuid, e.getMessage());
            return null;
        }
    }

    private Path resolveSourceWav(String voiceId) {
        AiPrompt active = loadActivePrompt();
        if (active == null || !StringUtils.hasText(active.getOpeningRemarks()) || !StringUtils.hasText(voiceId)) {
            return null;
        }
        String text = active.getOpeningRemarks().trim();
        Path wav = voiceDir(voiceId).resolve(OPENING_FILE);
        if (isValidCacheFor(text, voiceId) && Files.exists(wav)) {
            return wav;
        }
        return null;
    }

    private AiPrompt loadActivePrompt() {
        return aiPromptMapper.selectOne(
                new LambdaQueryWrapper<AiPrompt>().eq(AiPrompt::getIsActive, 1).last("LIMIT 1"));
    }

    public Path getFsSharedPath() {
        return isReady() ? fsLegacySharedPath() : null;
    }

    private void publishLegacyAlias(Path localWav) throws Exception {
        Path shared = fsLegacySharedPath();
        Files.createDirectories(shared.getParent());
        Files.copy(localWav, shared, StandardCopyOption.REPLACE_EXISTING);
        Path legacyLocal = localCachePath().resolve(LEGACY_ACTIVE_FILE);
        Files.createDirectories(legacyLocal.getParent());
        Files.copy(localWav, legacyLocal, StandardCopyOption.REPLACE_EXISTING);
    }

    private boolean isValidLegacyCache(String openingText) {
        Path localWav = localCachePath().resolve(LEGACY_ACTIVE_FILE);
        Path meta = localCachePath().resolve(LEGACY_ACTIVE_META);
        try {
            if (!Files.exists(localWav) || Files.size(localWav) <= 44 || !Files.exists(meta)) {
                return false;
            }
            String voiceId = voiceCatalog.resolveActiveVoiceId();
            if (!Files.readString(meta, StandardCharsets.UTF_8).trim()
                    .equals(cacheSignature(openingText, voiceId))) {
                return false;
            }
            return isDurationValidForText(localWav, openingText);
        } catch (Exception e) {
            return false;
        }
    }

    private Path voiceDir(String voiceId) {
        return localCachePath().resolve(FixedPhraseVoiceCatalogService.voiceKey(voiceId));
    }

    private Path localCachePath() {
        String dir = aiVoiceProperties.getOpeningVoiceCacheDir();
        if (!StringUtils.hasText(dir)) {
            dir = "./uploads/tts/opening";
        }
        return Path.of(dir.replace('/', '\\'));
    }

    private Path fsLegacySharedPath() {
        String dir = aiVoiceProperties.getFsSharedWavDir();
        if (!StringUtils.hasText(dir)) {
            dir = aiVoiceProperties.getFsTempWavDir();
        }
        return Path.of(dir.replace('/', '\\'), LEGACY_ACTIVE_FILE);
    }

    private Path callSharedPath(String fsUuid) {
        String dir = aiVoiceProperties.getFsSharedWavDir();
        if (!StringUtils.hasText(dir)) {
            dir = aiVoiceProperties.getFsTempWavDir();
        }
        String fileName = "aicall_" + fsUuid.replace("-", "") + ".wav";
        return Path.of(dir.replace('/', '\\'), fileName);
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

    private static String maskVoice(String voiceId) {
        if (!StringUtils.hasText(voiceId)) {
            return "(未配置)";
        }
        return voiceId.length() <= 20 ? voiceId : voiceId.substring(0, 12) + "…";
    }

    /** 按字数估算最短合理时长，过短说明预合成被截断或话术已变更 */
    static long expectedOpeningDurationMs(String openingText) {
        if (!StringUtils.hasText(openingText)) {
            return 1500;
        }
        return Math.max(1800, openingText.trim().length() * 120L);
    }

    static boolean isDurationValidForText(Path wav, String openingText) {
        long actual = TelephonyWavUtil.pcmDurationMs(wav);
        if (actual <= 0) {
            return false;
        }
        long expected = expectedOpeningDurationMs(openingText);
        return actual >= expected * 55 / 100;
    }
}
