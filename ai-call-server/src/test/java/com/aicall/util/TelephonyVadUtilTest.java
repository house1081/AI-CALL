package com.aicall.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TelephonyVadUtilTest {

    private static final int RATE = 8000;

    @Test
    void hasAnySpeech_triggersOnSingleSpike() {
        short[] pcm = silence(RATE); // 1s
        // 单帧尖峰杂音
        for (int i = 160; i < 320; i++) {
            pcm[i] = 3000;
        }
        assertTrue(TelephonyVadUtil.hasAnySpeech(pcm, 200));
    }

    @Test
    void hasSustainedSpeech_ignoresShortNoiseSpike() {
        short[] pcm = silence(RATE);
        for (int i = 160; i < 320; i++) {
            pcm[i] = 3000; // 20ms spike
        }
        assertFalse(TelephonyVadUtil.hasSustainedSpeech(pcm, RATE, 200, 200));
    }

    @Test
    void hasSustainedSpeech_detectsRealSpeech() {
        short[] pcm = silence(RATE);
        // 连续 240ms 语音
        for (int i = 0; i < RATE * 240 / 1000; i++) {
            pcm[i] = 2500;
        }
        assertTrue(TelephonyVadUtil.hasSustainedSpeech(pcm, RATE, 200, 200));
    }

    private static short[] silence(int samples) {
        return new short[samples];
    }
}
