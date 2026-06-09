package com.aicall.service;

import com.aicall.config.AiVoiceProperties;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

/**
 * 相同话术 TTS 结果缓存，降低 CosyVoice 调用频率、缓解 428 限流。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TtsPhraseCacheService {

    private static final int MAX_ENTRIES = 128;

    private final AiVoiceProperties aiVoiceProperties;
    private final DashScopeVoiceTtsService dashScopeVoiceTtsService;

    private final Map<String, Path> cache = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Path> eldest) {
            return size() > MAX_ENTRIES;
        }
    };

    private volatile boolean commonPhrasesWarmed;

    /** 启动后后台预热静默追问/没听清等高频话术，降低首通 TTS 延迟 */
    @EventListener(ContextRefreshedEvent.class)
    public void warmCommonPhrasesOnStartup() {
        if (commonPhrasesWarmed || !isAvailable()) {
            return;
        }
        commonPhrasesWarmed = true;
        List<String> phrases = List.of(
                "您好，请问您这边可以听到吗？",
                "您好，请问是否可以正常沟通？",
                "您好，我没听清，请您再说一遍。",
                "不好意思，这边信号可能有点不好，您那边声音有点小，麻烦您再说一遍好吗？"
        );
        CompletableFuture.runAsync(() -> {
            for (String phrase : phrases) {
                try {
                    Path tmp = cacheDir().resolve("warm_" + phrase.hashCode() + ".wav");
                    synthesizeToFile(tmp, phrase);
                } catch (Exception e) {
                    log.debug("[TTS缓存] 预热跳过 phrase={}: {}", phrase, e.getMessage());
                }
            }
            log.info("[TTS缓存] 常用话术预热完成 count={}", phrases.size());
        }, Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "tts-phrase-warm");
            t.setDaemon(true);
            return t;
        }));
    }

    public boolean isAvailable() {
        return dashScopeVoiceTtsService.isAvailable();
    }

    public Path synthesizeToFile(Path out, String text) {
        return synthesizeToFile(out, text, null);
    }

    public Path synthesizeToFile(Path out, String text, String voiceId) {
        if (!StringUtils.hasText(text) || !isAvailable()) {
            return null;
        }
        String key = cacheKey(text, voiceId);
        synchronized (cache) {
            Path hit = cache.get(key);
            if (hit != null && Files.exists(hit) && fileSize(hit) > 44) {
                try {
                    Files.copy(hit, out, StandardCopyOption.REPLACE_EXISTING);
                    log.debug("[TTS缓存] 命中 key={} bytes={}", key.substring(0, 8), Files.size(out));
                    return out;
                } catch (Exception e) {
                    cache.remove(key);
                }
            }
        }
        Path built = dashScopeVoiceTtsService.synthesizeToFile(out, text, voiceId);
        if (built == null || !Files.exists(built) || fileSize(built) <= 44) {
            return built;
        }
        try {
            Path store = cacheDir().resolve(key + ".wav");
            Files.createDirectories(store.getParent());
            Files.copy(built, store, StandardCopyOption.REPLACE_EXISTING);
            synchronized (cache) {
                cache.put(key, store);
            }
        } catch (Exception e) {
            log.debug("[TTS缓存] 写入失败: {}", e.getMessage());
        }
        return built;
    }

    private Path cacheDir() {
        String dir = aiVoiceProperties.getTtsPhraseCacheDir();
        if (!StringUtils.hasText(dir)) {
            dir = "./uploads/tts/phrases";
        }
        return Path.of(dir.replace('/', '\\'));
    }

    private String cacheKey(String text) {
        return cacheKey(text, null);
    }

    private String cacheKey(String text, String voiceId) {
        String voice = StringUtils.hasText(voiceId)
                ? dashScopeVoiceTtsService.voiceCacheSignature(voiceId)
                : dashScopeVoiceTtsService.voiceCacheSignature();
        String raw = voice + "|" + text.trim();
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
