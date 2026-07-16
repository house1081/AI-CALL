package com.aicall.service;

import com.aicall.config.AiVoiceProperties;
import com.aicall.entity.DialogTrainingQa;
import com.aicall.mapper.DialogTrainingQaMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * FAQ/主线应答 wav 路径缓存：避免每轮匹配重复 DB 查询与磁盘 stat。
 */
@Slf4j
@Service
public class KbAnswerWavCacheService {

    private final DialogTrainingQaMapper dialogTrainingQaMapper;
    private final DialogTrainingQaService dialogTrainingQaService;
    private final RecordingOnlyPlaybackService recordingOnlyPlaybackService;
    private final AiVoiceProperties aiVoiceProperties;

    /** Optional.empty() = 负缓存（无可用 wav） */
    private final ConcurrentHashMap<String, Optional<String>> cache = new ConcurrentHashMap<>();

    public KbAnswerWavCacheService(DialogTrainingQaMapper dialogTrainingQaMapper,
                                   @Lazy DialogTrainingQaService dialogTrainingQaService,
                                   RecordingOnlyPlaybackService recordingOnlyPlaybackService,
                                   AiVoiceProperties aiVoiceProperties) {
        this.dialogTrainingQaMapper = dialogTrainingQaMapper;
        this.dialogTrainingQaService = dialogTrainingQaService;
        this.recordingOnlyPlaybackService = recordingOnlyPlaybackService;
        this.aiVoiceProperties = aiVoiceProperties;
    }

    public String resolveByQaId(Integer qaId) {
        if (qaId == null) {
            return null;
        }
        return resolve("qa:" + qaId, () -> loadWavFromQaRow(dialogTrainingQaMapper.selectById(qaId)));
    }

    public String resolveByAnswerText(int kbId, String answerText) {
        if (!StringUtils.hasText(answerText)) {
            return null;
        }
        String key = "kb:" + kbId + ":ans:" + normalizeAnswerKey(answerText);
        return resolve(key, () -> loadWavFromQaRow(dialogTrainingQaService.findByAnswerText(kbId, answerText.trim())));
    }

    public String resolveExplicit(String wavPath) {
        if (!StringUtils.hasText(wavPath)) {
            return null;
        }
        String normalized = wavPath.trim();
        return resolve("path:" + normalized, () -> isPlayablePath(normalized) ? normalized : null);
    }

    public void invalidateAll() {
        int size = cache.size();
        cache.clear();
        if (size > 0) {
            log.info("[WAV缓存] 已清空 entries={}", size);
        }
    }

    public void invalidateQa(Integer qaId) {
        if (qaId == null) {
            return;
        }
        cache.remove("qa:" + qaId);
    }

    public void invalidateKbAnswer(int kbId, String answerText) {
        if (!StringUtils.hasText(answerText)) {
            return;
        }
        cache.remove("kb:" + kbId + ":ans:" + normalizeAnswerKey(answerText));
    }

    private String resolve(String key, Supplier<String> loader) {
        Optional<String> hit = cache.get(key);
        if (hit != null) {
            return hit.orElse(null);
        }
        String path = loader.get();
        if (StringUtils.hasText(path) && isPlayablePath(path)) {
            put(key, path);
            return path;
        }
        putNegative(key);
        return null;
    }

    private void put(String key, String path) {
        evictIfNeeded();
        cache.put(key, Optional.of(path));
    }

    private void putNegative(String key) {
        evictIfNeeded();
        cache.put(key, Optional.empty());
    }

    private void evictIfNeeded() {
        int max = Math.max(512, aiVoiceProperties.getKbWavCacheMaxEntries());
        if (cache.size() < max) {
            return;
        }
        int remove = Math.max(1, max / 10);
        var it = cache.keySet().iterator();
        while (remove > 0 && it.hasNext()) {
            it.next();
            it.remove();
            remove--;
        }
    }

    private String loadWavFromQaRow(DialogTrainingQa qa) {
        if (qa == null || !StringUtils.hasText(qa.getAnswerWavPath())) {
            return null;
        }
        return qa.getAnswerWavPath().trim();
    }

    private boolean isPlayablePath(String wavPath) {
        Path p = recordingOnlyPlaybackService.resolveExistingWav(wavPath);
        return p != null;
    }

    static String normalizeAnswerKey(String answerText) {
        return answerText.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }
}
