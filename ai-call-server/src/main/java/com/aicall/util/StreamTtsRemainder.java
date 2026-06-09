package com.aicall.util;

import org.springframework.util.StringUtils;

/**
 * 流式首句 TTS 已播前缀与最终全文对齐，计算待补播后缀。
 */
public final class StreamTtsRemainder {

    private StreamTtsRemainder() {
    }

    public static String unplayed(String fullReply, String streamedPrefix) {
        if (!StringUtils.hasText(fullReply)) {
            return "";
        }
        String full = fullReply.trim();
        if (!StringUtils.hasText(streamedPrefix)) {
            return full;
        }
        String prefix = streamedPrefix.trim();
        if (full.equals(prefix)) {
            return "";
        }
        if (full.startsWith(prefix)) {
            return full.substring(prefix.length()).trim();
        }
        int overlap = longestSuffixPrefixOverlap(prefix, full);
        if (overlap > 0 && full.length() > overlap) {
            return full.substring(overlap).trim();
        }
        return full;
    }

    private static int longestSuffixPrefixOverlap(String prefix, String full) {
        int max = Math.min(prefix.length(), full.length());
        for (int len = max; len > 0; len--) {
            if (prefix.regionMatches(prefix.length() - len, full, 0, len)) {
                return len;
            }
        }
        return 0;
    }
}
