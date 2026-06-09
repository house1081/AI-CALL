package com.aicall.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpeningVoiceCacheServiceTest {

    @Test
    void expectedOpeningDurationMs_scalesWithTextLength() {
        long shortText = OpeningVoiceCacheService.expectedOpeningDurationMs("您好");
        long longText = OpeningVoiceCacheService.expectedOpeningDurationMs(
                "喂，您好！我是建行服务商。专门帮客户走建行绿色通道，可以提额降息，要咨询了解下吗？");
        assertTrue(shortText >= 1800);
        assertTrue(longText > shortText);
    }

    @Test
    void isDurationValidForText_rejectsTooShortRelativeToScript() throws Exception {
        String script = "喂，您好！我是建行服务商。专门帮客户走建行绿色通道，可以提额降息，要咨询了解下吗？";
        long expected = OpeningVoiceCacheService.expectedOpeningDurationMs(script);
        int pcmBytes = (int) (expected * 55 / 100 * 8000 / 1000) * 2;
        byte[] pcm = new byte[Math.max(44, pcmBytes - 8000)];
        java.nio.file.Path wav = java.nio.file.Files.createTempFile("opening-short", ".wav");
        try {
            byte[] file = com.aicall.util.TelephonyWavUtil.buildWavFromRawPcm(pcm, 8000, 1, 16);
            java.nio.file.Files.write(wav, file);
            assertFalse(OpeningVoiceCacheService.isDurationValidForText(wav, script));
        } finally {
            java.nio.file.Files.deleteIfExists(wav);
        }
    }
}
