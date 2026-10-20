package com.aicall.common;

/** 通话模式 */
public final class CallMode {
    public static final String AI_TASK = "ai_task";
    public static final String CLICK_CALL = "click_call";
    public static final String HUMAN_TRANSFER = "human_transfer";

    public static String label(String mode) {
        if (mode == null) {
            return "-";
        }
        return switch (mode) {
            case AI_TASK -> "AI外呼";
            case CLICK_CALL -> "点呼";
            case HUMAN_TRANSFER -> "转人工";
            default -> mode;
        };
    }

    private CallMode() {
    }
}
