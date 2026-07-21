package com.aicall.common;

/**
 * 外呼句末静默档位：稳健（嘈杂移动线）/ 均衡（默认）/ 极速（安静固话）。
 * 任务级 &gt; 全局 DB &gt; application.yml 默认。
 */
public final class SilenceProfile {

    /** 批量电销 / 嘈杂移动线 */
    public static final String STABLE = "stable";
    /** 默认：延迟与抗噪折中 */
    public static final String BALANCED = "balanced";
    /** 精品回访 / 安静固话 */
    public static final String FAST = "fast";

    public record Params(String profile, int userSilenceMs, int debounceMs, double vadThreshold) {
    }

    private SilenceProfile() {
    }

    public static boolean isValid(String profile) {
        String p = safe(profile);
        return STABLE.equalsIgnoreCase(p) || BALANCED.equalsIgnoreCase(p) || FAST.equalsIgnoreCase(p);
    }

    public static String normalize(String profile) {
        String p = safe(profile);
        if (FAST.equalsIgnoreCase(p)) {
            return FAST;
        }
        if (STABLE.equalsIgnoreCase(p)) {
            return STABLE;
        }
        return BALANCED;
    }

    public static Params resolve(String profile) {
        String p = normalize(profile);
        if (FAST.equals(p)) {
            return new Params(FAST, 280, 50, 0.26);
        }
        if (STABLE.equals(p)) {
            return new Params(STABLE, 800, 120, 0.38);
        }
        // 均衡：比 stable 更快开口，比 fast 更抗噪
        return new Params(BALANCED, 450, 80, 0.32);
    }

    public static String label(String profile) {
        String p = normalize(profile);
        if (FAST.equals(p)) {
            return "极速";
        }
        if (STABLE.equals(p)) {
            return "稳健";
        }
        return "均衡";
    }

    private static String safe(String profile) {
        return profile != null ? profile.trim() : "";
    }
}
