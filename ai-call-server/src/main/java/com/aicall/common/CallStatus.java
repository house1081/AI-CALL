package com.aicall.common;

/** PRD 通话状态：0进行中 1接通 2无人接听 3空号 4关机 5拒接 6拨号失败 */
public final class CallStatus {
    public static final int IN_PROGRESS = 0;
    public static final int CONNECTED = 1;
    public static final int NO_ANSWER = 2;
    public static final int EMPTY = 3;
    public static final int POWER_OFF = 4;
    public static final int REJECT = 5;
    public static final int DIAL_FAIL = 6;

    public static String label(int status) {
        return switch (status) {
            case IN_PROGRESS -> "进行中";
            case CONNECTED -> "接通";
            case NO_ANSWER -> "无人接听";
            case EMPTY -> "空号";
            case POWER_OFF -> "关机";
            case REJECT -> "拒接";
            case DIAL_FAIL -> "拨号失败";
            default -> "未知";
        };
    }

    public static boolean isConnected(int status) {
        return status == CONNECTED;
    }

    private CallStatus() {}
}
