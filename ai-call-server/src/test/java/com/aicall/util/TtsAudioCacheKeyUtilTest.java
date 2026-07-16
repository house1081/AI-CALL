package com.aicall.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TtsAudioCacheKeyUtilTest {

    @Test
    void normalizeUserQuestion_appliesAsrAndFillerStrip() {
        assertEquals("上征信", TtsAudioCacheKeyUtil.normalizeUserQuestion("嗯，上不上征信？"));
        assertEquals("查征信", TtsAudioCacheKeyUtil.normalizeUserQuestion("查不查征信啊"));
    }

    @Test
    void normalizeUserQuestion_sameKeyForVariants() {
        String a = TtsAudioCacheKeyUtil.normalizeUserQuestion("利息多少？");
        String b = TtsAudioCacheKeyUtil.normalizeUserQuestion("嗯 利息多少");
        assertEquals(a, b);
        assertTrue(a.length() >= 2);
    }
}
