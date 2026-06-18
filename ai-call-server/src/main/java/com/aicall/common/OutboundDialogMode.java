package com.aicall.common;

import org.springframework.util.StringUtils;

public final class OutboundDialogMode {

    public static final String AI_REALTIME = "ai_realtime";
    public static final String SMART_PRERECORD = "smart_prerecord";

    private OutboundDialogMode() {
    }

    public static String normalize(String mode) {
        if (SMART_PRERECORD.equalsIgnoreCase(StringUtils.trimWhitespace(mode))) {
            return SMART_PRERECORD;
        }
        return AI_REALTIME;
    }

    public static boolean isSmartPrerecord(String mode) {
        return SMART_PRERECORD.equals(normalize(mode));
    }
}
