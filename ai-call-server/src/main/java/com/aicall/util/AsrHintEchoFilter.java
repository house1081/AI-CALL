package com.aicall.util;

import org.springframework.util.StringUtils;

import java.util.regex.Pattern;

/**
 * 过滤 qwen3-asr-flash 将 asr-context-hint 整句误识别为客户说话的情况。
 */
public final class AsrHintEchoFilter {

    private static final Pattern PUNCT = Pattern.compile("[\\s，,。.!！?？~～、；;：:]+");

    private AsrHintEchoFilter() {
    }

    public static boolean isEcho(String asrText, String contextHint) {
        if (!StringUtils.hasText(asrText) || !StringUtils.hasText(contextHint)) {
            return false;
        }
        String text = compact(asrText);
        String hint = compact(contextHint);
        if (text.length() < 8 || hint.length() < 4) {
            return false;
        }
        if (text.equals(hint)) {
            return true;
        }
        if (text.length() >= 10 && hint.length() >= 10) {
            if (text.contains(hint) || hint.contains(text)) {
                return true;
            }
            if (overlapRatio(text, hint) >= 0.55) {
                return true;
            }
        }
        // 长句热词提示的典型回声
        return text.contains("金融贷款外呼电话") && text.contains("客户可能说");
    }

    private static String compact(String raw) {
        return PUNCT.matcher(raw.trim()).replaceAll("");
    }

    private static double overlapRatio(String a, String b) {
        String shorter = a.length() <= b.length() ? a : b;
        String longer = a.length() <= b.length() ? b : a;
        int hit = 0;
        for (int i = 0; i + 3 <= shorter.length(); i += 2) {
            String slice = shorter.substring(i, Math.min(i + 4, shorter.length()));
            if (longer.contains(slice)) {
                hit += slice.length();
            }
        }
        return hit / (double) shorter.length();
    }
}
