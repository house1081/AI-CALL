package com.aicall.util;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** PRD 计费规则：不足1分钟按1分钟 */
public final class BillingUtil {
    private BillingUtil() {}

    public static int ceilMinutes(int durationSeconds) {
        if (durationSeconds <= 0) {
            return 1;
        }
        return (int) Math.ceil(durationSeconds / 60.0);
    }

    public static BigDecimal calcAmount(int minutes, BigDecimal pricePerMinute) {
        return pricePerMinute.multiply(BigDecimal.valueOf(minutes))
                .setScale(4, RoundingMode.HALF_UP);
    }
}
