package com.aicall.util;

import org.springframework.util.StringUtils;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * TTS / 问答音频缓存键规范化：与 {@link com.aicall.service.CallAiVoiceService#playText} 播报前处理对齐。
 */
public final class TtsAudioCacheKeyUtil {

    private static final Pattern QUESTION_PUNCT = Pattern.compile(
            "[\\s\\p{Punct}，。！？；：、\"'「」『』（）()\\[\\]【】《》…—\\-~·]+");

    private TtsAudioCacheKeyUtil() {
    }

    /** 回答话术：限长 + 口语化，供短语 TTS 缓存键使用 */
    public static String normalizeReplyText(String text, int maxChars) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        String limited = SpeakTextLimiter.limit(text, maxChars);
        return OralScriptNormalizer.normalize(limited);
    }

    /** 用户问题：去标点空白，便于「同样的问题」命中 */
    public static String normalizeUserQuestion(String text) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        String s = text.trim().replace('\n', ' ');
        s = QUESTION_PUNCT.matcher(s).replaceAll("");
        s = s.replaceAll("\\s+", "");
        return s.toLowerCase(Locale.ROOT);
    }
}
