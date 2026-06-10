package com.aicall.util;

import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 金融外呼口语化：短句、卖点分句、去书面语，供 TTS 分句合成。
 */
public final class OralScriptNormalizer {

    private static final Pattern SENTENCE_SPLIT = Pattern.compile("(?<=[。！？；.!?;])");
    private static final Pattern FORMAL_PHRASES = Pattern.compile(
            "诸如|综上所述|也就是说|换言之|首先呢|其次呢|最后呢|那么呢");
    private static final Pattern SELLING_POINT = Pattern.compile(
            "额度|利息|利率|放款|到账|收费|杂费|手续|周转|抵押|征信");

    private OralScriptNormalizer() {
    }

    /** 播报前规范化：去书面语、合并空白 */
    public static String normalize(String text) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        String s = text.trim()
                .replace('\n', ' ')
                .replaceAll("\\s+", " ");
        s = FORMAL_PHRASES.matcher(s).replaceAll("");
        s = s.replace("。。", "。").replace("，，", "，");
        return s.trim();
    }

    /**
     * 拆成单句/单卖点，每段单独调 TTS。
     * 句号分句后，含卖点的长分句再按逗号拆。
     */
    public static List<String> splitForPlayback(String text, int maxChars) {
        String normalized = normalize(text);
        if (!StringUtils.hasText(normalized)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String sentence : SENTENCE_SPLIT.split(normalized)) {
            String s = sentence.trim();
            if (!StringUtils.hasText(s)) {
                continue;
            }
            if (s.length() <= maxChars && !shouldSplitAtClause(s)) {
                out.add(ensureEnding(s));
                continue;
            }
            out.addAll(splitSentenceWithSellingPoints(s, maxChars));
        }
        if (out.isEmpty()) {
            out.addAll(SpeakTextLimiter.splitWithinLimit(normalized, maxChars));
        }
        return out;
    }

    private static boolean shouldSplitAtClause(String sentence) {
        if (sentence.length() < 14) {
            return false;
        }
        return SELLING_POINT.matcher(sentence).find() && sentence.indexOf('，') > 0;
    }

    private static List<String> splitSentenceWithSellingPoints(String sentence, int maxChars) {
        List<String> out = new ArrayList<>();
        if (!shouldSplitAtClause(sentence)) {
            for (String part : SpeakTextLimiter.splitWithinLimit(sentence, maxChars)) {
                if (StringUtils.hasText(part)) {
                    out.add(ensureEnding(part));
                }
            }
            return out;
        }
        String[] clauses = sentence.split("(?<=[，,])");
        for (String clause : clauses) {
            String c = clause.trim();
            if (!StringUtils.hasText(c)) {
                continue;
            }
            if (c.length() <= maxChars) {
                out.add(ensureEnding(c));
            } else {
                for (String part : SpeakTextLimiter.splitWithinLimit(c, maxChars)) {
                    if (StringUtils.hasText(part)) {
                        out.add(ensureEnding(part));
                    }
                }
            }
        }
        return out;
    }

    private static String ensureEnding(String s) {
        if (!StringUtils.hasText(s)) {
            return "";
        }
        String t = s.trim();
        char last = t.charAt(t.length() - 1);
        if (last == '。' || last == '！' || last == '？' || last == '；'
                || last == '.' || last == '!' || last == '?' || last == ';') {
            return t;
        }
        if (last == '，' || last == ',') {
            return t.substring(0, t.length() - 1).trim() + "。";
        }
        return t + "。";
    }

    /** 根据上一句末标点估算句间空白（毫秒） */
    public static int pauseMsAfter(String sentence, int clausePauseMs, int sentencePauseMs, int defaultGapMs) {
        if (!StringUtils.hasText(sentence)) {
            return defaultGapMs;
        }
        char last = sentence.trim().charAt(sentence.trim().length() - 1);
        if (last == '，' || last == ',') {
            return Math.max(150, clausePauseMs);
        }
        if (last == '。' || last == '！' || last == '？' || last == '；'
                || last == '.' || last == '!' || last == '?' || last == ';') {
            return Math.max(250, sentencePauseMs);
        }
        return defaultGapMs;
    }
}
