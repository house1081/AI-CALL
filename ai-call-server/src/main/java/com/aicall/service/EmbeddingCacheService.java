package com.aicall.service;

import com.aicall.util.TtsAudioCacheKeyUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** RAG 查询向量 LRU 缓存，避免同一用户话术重复 embedding */
@Slf4j
@Service
public class EmbeddingCacheService {

    private static final int MAX_ENTRIES = 512;

    private final Map<String, float[]> cache = new LinkedHashMap<>(128, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, float[]> eldest) {
            return size() > MAX_ENTRIES;
        }
    };

    public float[] get(String text) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        String key = normalizeKey(text);
        synchronized (cache) {
            return cache.get(key);
        }
    }

    public void put(String text, float[] vec) {
        if (!StringUtils.hasText(text) || vec == null || vec.length == 0) {
            return;
        }
        String key = normalizeKey(text);
        synchronized (cache) {
            cache.put(key, vec);
        }
    }

    private static String normalizeKey(String text) {
        String n = TtsAudioCacheKeyUtil.normalizeUserQuestion(text);
        if (StringUtils.hasText(n)) {
            return n;
        }
        return text.trim().toLowerCase(Locale.ROOT);
    }
}
