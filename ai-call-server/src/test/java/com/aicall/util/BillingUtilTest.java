package com.aicall.util;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BillingUtilTest {

    @Test
    void ceilMinutes_zeroOrShort() {
        assertEquals(1, BillingUtil.ceilMinutes(0));
        assertEquals(1, BillingUtil.ceilMinutes(1));
        assertEquals(1, BillingUtil.ceilMinutes(59));
        assertEquals(1, BillingUtil.ceilMinutes(60));
    }

    @Test
    void ceilMinutes_overOneMinute() {
        assertEquals(2, BillingUtil.ceilMinutes(61));
        assertEquals(3, BillingUtil.ceilMinutes(125));
    }

    @Test
    void calcAmount() {
        BigDecimal amount = BillingUtil.calcAmount(3, new BigDecimal("0.1200"));
        assertEquals(0, new BigDecimal("0.3600").compareTo(amount));
    }
}
