package com.aicall.util;

import org.springframework.util.StringUtils;

/**
 * 流式首句 TTS 已播前缀与最终全文对齐，计算待补播后缀。
 */
public final class StreamTtsRemainder {

    private StreamTtsRemainder() {
    }

    /**
     * 前缀与最终全文是否可无缝衔接（避免润色后全文与已播原文错位导致整段重播叠音）。
     */
    public static boolean canContinueFromPrefix(String fullReply, String streamedPrefix) {
        if (!StringUtils.hasText(fullReply)) {
            return true;
        }
        if (!StringUtils.hasText(streamedPrefix)) {
            return true;
        }
        String full = fullReply.trim();
        String prefix = streamedPrefix.trim();
        if (full.equals(prefix) || full.startsWith(prefix)) {
            return true;
        }
        int overlap = longestSuffixPrefixOverlap(prefix, full);
        // 至少重叠 8 字，或重叠覆盖前缀一半以上，才认为可续播
        return overlap >= 8 || (overlap > 0 && overlap * 2 >= prefix.length());
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
        if (!canContinueFromPrefix(full, prefix)) {
            // 错位：返回全文，由调用方决定停掉前缀后整段重播
            return full;
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
