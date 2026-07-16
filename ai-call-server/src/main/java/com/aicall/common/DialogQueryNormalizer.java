package com.aicall.common;

import org.springframework.util.StringUtils;

/**
 * ASR 用户句归一化，供向量检索与匹配（非逐句手工关键词）。
 */
public final class DialogQueryNormalizer {

    private DialogQueryNormalizer() {
    }

    public static String forRetrieval(String userText) {
        if (!StringUtils.hasText(userText)) {
            return "";
        }
        String t = userText.trim()
                .replaceAll("^[嗯啊哦呃唉哈\\s，,。.!！?？~～]+", "")
                .replaceAll("\\s+", "");
        if (t.length() <= 1) {
            return userText.trim();
        }
        return t;
    }
}
