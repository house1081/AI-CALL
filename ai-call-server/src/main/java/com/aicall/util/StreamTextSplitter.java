package com.aicall.util;

import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** 按自然句切分，用于流式 TTS 播报 */
public final class StreamTextSplitter {

    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[。！？；.!?;])");

    private StreamTextSplitter() {
    }

    public static List<String> splitForStreamTts(String text, int maxChars) {
        if (!StringUtils.hasText(text)) {
            return List.of();
        }
        String normalized = text.replace("\n", " ").trim();
        List<String> sentences = new ArrayList<>();
        for (String part : SENTENCE_END.split(normalized)) {
            String s = part.trim();
            if (!StringUtils.hasText(s)) {
                continue;
            }
            if (s.length() <= maxChars) {
                sentences.add(s);
            } else {
                sentences.addAll(SpeakTextLimiter.splitWithinLimit(s, maxChars));
            }
        }
        if (sentences.isEmpty()) {
            sentences.addAll(SpeakTextLimiter.splitWithinLimit(normalized, maxChars));
        }
        return sentences;
    }
}
