package com.aicall.common;

/**
 * 外呼句末静默档位：稳健（嘈杂移动线）/ 极速（安静固话）。
 * 任务级 &gt; 全局 DB &gt; application.yml 默认。
 */
public final class SilenceProfile {

    /** 批量电销 / 嘈杂移动线 */
    public static final String STABLE = "stable";
    /** 精品回访 / 安静固话 */
    public static final String FAST = "fast";

    public record Params(String profile, int userSilenceMs, int debounceMs, double vadThreshold) {
    }

    private SilenceProfile() {
    }

    public static boolean isValid(String profile) {
        return STABLE.equalsIgnoreCase(safe(profile)) || FAST.equalsIgnoreCase(safe(profile));
    }

    public static String normalize(String profile) {
        return FAST.equalsIgnoreCase(safe(profile)) ? FAST : STABLE;
    }

    public static Params resolve(String profile) {
        if (FAST.equalsIgnoreCase(safe(profile))) {
            return new Params(FAST, 150, 35, 0.26);
        }
        return new Params(STABLE, 800, 120, 0.35);
    }

    public static String label(String profile) {
        return FAST.equals(normalize(profile)) ? "极速" : "稳健";
    }

    private static String safe(String profile) {
        return profile != null ? profile.trim() : "";
    }
}
