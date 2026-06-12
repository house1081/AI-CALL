package com.aicall.common;

import org.springframework.util.StringUtils;

/** CosyVoice 外呼音色来源：复刻 voice_id 或系统预置音色 */
public final class CosyVoiceVoiceMode {

    public static final String CLONE = "clone";
    public static final String SYSTEM = "system";

    private CosyVoiceVoiceMode() {
    }

    public static String normalize(String mode) {
        if (!StringUtils.hasText(mode)) {
            return CLONE;
        }
        String m = mode.trim().toLowerCase();
        return SYSTEM.equals(m) ? SYSTEM : CLONE;
    }

    public static boolean isSystem(String mode) {
        return SYSTEM.equals(normalize(mode));
    }
}
