package com.aicall.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AsrFillerFilterTest {

    @Test
    void validShortAnswersAreNotFiltered() {
        assertTrue(AsrFillerFilter.isValidShortAnswer("谁呀？"));
        assertTrue(AsrFillerFilter.isValidShortAnswer("可以"));
        assertTrue(AsrFillerFilter.isValidShortAnswer("对。"));
        assertTrue(AsrFillerFilter.isValidShortAnswer("是"));
        assertFalse(AsrFillerFilter.isLikelyShortMisrecognition("谁呀", pcm(1800)));
    }

    @Test
    void pureFillerMayBeFilteredOnLongRecording() {
        assertTrue(AsrFillerFilter.isPureFillerOnly("嗯"));
        assertFalse(AsrFillerFilter.isPureFillerOnly("可以"));
        assertTrue(AsrFillerFilter.isLikelyHallucinatedFiller("嗯", pcm(2000), 80));
    }

    private static WavPcmUtil.MonoPcm pcm(int durationMs) {
        int samples = durationMs * 8;
        short[] data = new short[samples];
        for (int i = 0; i < samples; i++) {
            data[i] = (short) (800 * Math.sin(i / 8.0));
        }
        return new WavPcmUtil.MonoPcm(8000, data);
    }
}
