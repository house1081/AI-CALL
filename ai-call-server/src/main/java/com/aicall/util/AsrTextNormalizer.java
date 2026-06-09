package com.aicall.util;

import org.springframework.util.StringUtils;

import java.util.regex.Pattern;

/**
 * 电话 ASR 常见误识别与口语数字归一化。
 */
public final class AsrTextNormalizer {

    private static final Pattern PUNCT = Pattern.compile("[\\s，,。.!！?？~～、]+");

    private AsrTextNormalizer() {
    }

    public static String normalize(String raw) {
        if (!StringUtils.hasText(raw)) {
            return "";
        }
        String t = raw.trim();
        // 易与「嗯」混淆的短音
        if (t.matches("^(八|巴|把|爸|吧)[。.]?$")) {
            return "八十";
        }
        if (t.matches("^(五|舞|无)[。.]?$")) {
            return "五十";
        }
        if (t.matches("^(三|山|散)[。.]?$")) {
            return "三十";
        }
        if (t.matches("^(二|两|尔)[。.]?$")) {
            return "二十";
        }
        // 常见误识别
        t = t.replace("听不道", "听不到")
                .replace("听不见你", "听不见")
                .replace("你不讲话", "你没讲话")
                .replace("乐数", "乐数云")
                .replace("贷款代", "贷款")
                .replace("周转金", "周转")
                .replace("有啊有", "有啊")
                .replace("哦有", "哦，有")
                .replace("嗯有", "嗯，有");
        t = t.replace("八零", "八十")
                .replace("8十", "80")
                .replace("８十", "80");
        return t;
    }

    /** 是否为明确的额度数字回答（含中文数字或 2~3 位阿拉伯数字） */
    public static boolean isNumericAmountUtterance(String text) {
        if (!StringUtils.hasText(text)) {
            return false;
        }
        String n = PUNCT.matcher(text.trim()).replaceAll("");
        if (n.matches("\\d{1,3}")) {
            return true;
        }
        if (n.matches("\\d{4,8}")) {
            return true;
        }
        return n.matches("(八十|九十|七十|六十|五十|四十|三十|二十|十|两|一|二|三|四|五|六|七|八|九)")
                || n.matches("[一二三四五六七八九十两千]{1,4}")
                || n.contains("千万") || n.contains("百万") || n.contains("万");
    }
}
