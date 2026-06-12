package com.aicall.common;

import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Optional;

/**
 * CosyVoice 系统预置音色（适用于 cosyvoice-v3-flash / cosyvoice-v2，不含 v3.5 复刻专用模型）。
 */
public final class CosyVoiceSystemVoiceCatalog {

    public record Option(String voiceId, String label, String model) {
    }

    private static final String DEFAULT_MODEL = "cosyvoice-v3-flash";

    private static final List<Option> VOICE_OPTIONS = List.of(
            new Option("longanyang", "龙安洋（男，稳重大气）", DEFAULT_MODEL),
            new Option("longanhuan_v3", "龙安欢（女，亲切自然）", DEFAULT_MODEL),
            new Option("longfei_v3", "龙飞（男，清晰专业）", DEFAULT_MODEL),
            new Option("longling_v3", "龙铃（女，温柔）", DEFAULT_MODEL),
            new Option("longshanshan_v3", "龙珊珊（女，客服感）", DEFAULT_MODEL),
            new Option("longlaotie_v3", "龙老铁（男，热情）", DEFAULT_MODEL),
            new Option("longxiaochun_v2", "龙小淳（女，v2 经典）", "cosyvoice-v2")
    );

    private CosyVoiceSystemVoiceCatalog() {
    }

    public static List<Option> listOptions() {
        return VOICE_OPTIONS;
    }

    public static String defaultVoiceId() {
        return "longanyang";
    }

    public static String defaultModel() {
        return DEFAULT_MODEL;
    }

    public static Optional<Option> find(String voiceId) {
        if (!StringUtils.hasText(voiceId)) {
            return Optional.empty();
        }
        String id = voiceId.trim();
        return VOICE_OPTIONS.stream().filter(o -> o.voiceId().equals(id)).findFirst();
    }

    public static String resolveModel(String voiceId) {
        return find(voiceId).map(Option::model).orElse(DEFAULT_MODEL);
    }

    public static String normalizeVoiceId(String voiceId) {
        if (find(voiceId).isPresent()) {
            return voiceId.trim();
        }
        return defaultVoiceId();
    }
}
