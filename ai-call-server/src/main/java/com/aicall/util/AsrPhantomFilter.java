package com.aicall.util;

import org.springframework.util.StringUtils;

import java.util.Set;

/** 过滤 ASR 幻听：系统提示语、热词回声、AI 自己的「没听清」等 */
public final class AsrPhantomFilter {

    private static final Set<String> SYSTEM_PHRASES = Set.of(
            "听不清", "没听清", "听不太清", "请您再说一遍", "您好我没听清",
            "您好，我没听清，请您再说一遍", "再说一遍", "没听见", "听不见了");

    private AsrPhantomFilter() {
    }

    public static boolean isSystemPhantom(String text) {
        if (!StringUtils.hasText(text)) {
            return false;
        }
        String n = text.trim().replaceAll("[\\s，,。.!！?？~～、；;]+", "");
        if (SYSTEM_PHRASES.contains(n)) {
            return true;
        }
        return n.contains("没听清") || (n.contains("听不清") && n.length() <= 12);
    }
}
