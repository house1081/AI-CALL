package com.aicall.util;

import org.springframework.util.StringUtils;

import java.util.Set;

/**
 * 过滤 ASR 在静音/低信噪比下的纯语气词幻听（尤其 qwen-asr 常返回「嗯」）。
 * 注意：「是」「对」「好」「可以」等是外呼常见有效短答，不可当作幻听丢弃。
 */
public final class AsrFillerFilter {

    /** 仅无明确语义的语气词，长录音+低信噪比时才视为幻听 */
    private static final Set<String> PURE_FILLERS = Set.of("嗯", "啊", "哦", "呃", "额");

    /** 外呼/训练常见有效短答（含 2 字），不做短答幻听过滤 */
    private static final Set<String> VALID_SHORT_ANSWERS = Set.of(
            "是", "对", "好", "行", "嗯", "啊", "哦", "喂", "呃", "额",
            "可以", "不用", "没有", "有啊", "谁呀", "什么", "多少", "干嘛", "好吧",
            "要的", "需要", "不要", "不行", "在的", "哪位", "你说", "讲吧", "说吧",
            "介绍一下", "考虑", "有兴趣", "没兴趣", "没打算", "有打算");

    private AsrFillerFilter() {
    }

    public static boolean isValidShortAnswer(String text) {
        if (!StringUtils.hasText(text)) {
            return false;
        }
        String n = normalize(text);
        if (VALID_SHORT_ANSWERS.contains(n)) {
            return true;
        }
        return n.length() >= 3;
    }

    public static boolean isPureFillerOnly(String text) {
        if (!StringUtils.hasText(text)) {
            return false;
        }
        return PURE_FILLERS.contains(normalize(text));
    }

    /** @deprecated 使用 {@link #isPureFillerOnly} */
    @Deprecated
    public static boolean isFillerOnly(String text) {
        return isPureFillerOnly(text);
    }

    /** 短答幻听：录音较长却只识别 1 个纯语气词 */
    public static boolean isLikelyShortMisrecognition(String text, WavPcmUtil.MonoPcm pcm) {
        if (!StringUtils.hasText(text) || pcm == null || isValidShortAnswer(text)) {
            return false;
        }
        String n = normalize(text);
        if (n.length() > 1) {
            return false;
        }
        return pcm.durationMs() >= 1500 && TelephonyVadUtil.hasAnySpeech(pcm.samples(), 120);
    }

    /** 录音较长且检测到有效人声，却只识别出单个纯语气词 → 视为幻听 */
    public static boolean isLikelyHallucinatedFiller(String text, WavPcmUtil.MonoPcm pcm, int energyThreshold) {
        if (!isPureFillerOnly(text) || pcm == null || pcm.samples() == null) {
            return false;
        }
        int durationMs = pcm.durationMs();
        if (durationMs < 800) {
            return false;
        }
        return TelephonyVadUtil.hasAnySpeech(pcm.samples(), energyThreshold);
    }

    private static String normalize(String text) {
        return text.trim().replaceAll("[\\s，,。.!！?？~～、；;]+", "");
    }
}
