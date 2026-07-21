package com.aicall.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MainFlowContextBridgeTest {

    @Test
    void bridge_ackHasCarThenAskHouse() {
        String out = MainFlowContextBridge.bridge(
                "有",
                "嗯，那您名下有车吗？",
                "再问一下，您名下有房吗？");
        assertTrue(out.contains("有车") || out.startsWith("好的"));
        assertTrue(out.contains("有房"));
    }

    @Test
    void bridge_ackNoCar() {
        String out = MainFlowContextBridge.bridge(
                "没有",
                "嗯，那您名下有车吗？",
                "再问一下，您名下有房吗？");
        assertTrue(out.contains("没有车") || out.startsWith("嗯"));
        assertTrue(out.contains("有房"));
    }

    @Test
    void bridge_amountThenJob() {
        String out = MainFlowContextBridge.bridge(
                "五十万",
                "那您大概要多少资金呢？",
                "行，我这边给您对一下产品，先问一下，您是上班还是做生意呀？");
        assertTrue(out.contains("五十") || out.startsWith("好的"));
        assertTrue(out.contains("上班") || out.contains("做生意"));
    }

    @Test
    void shouldLlmPolish_shortAckFalse() {
        assertFalse(MainFlowContextBridge.shouldLlmPolish("有"));
        assertFalse(MainFlowContextBridge.shouldLlmPolish("嗯"));
        assertTrue(MainFlowContextBridge.shouldLlmPolish("我在工地干活的"));
    }
}
