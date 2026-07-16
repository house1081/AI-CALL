package com.aicall.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KbAnswerWavCacheServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void cachesPlayablePathByQaId() throws Exception {
        Path wav = tempDir.resolve("a.wav");
        Files.write(wav, new byte[100]);

        var mapper = mock(com.aicall.mapper.DialogTrainingQaMapper.class);
        var qaService = mock(DialogTrainingQaService.class);
        var playback = mock(RecordingOnlyPlaybackService.class);
        var props = new com.aicall.config.AiVoiceProperties();

        var qa = new com.aicall.entity.DialogTrainingQa();
        qa.setId(7);
        qa.setAnswerWavPath(wav.toString());
        when(mapper.selectById(7)).thenReturn(qa);
        when(playback.resolveExistingWav(anyString())).thenAnswer(inv -> {
            Path p = Path.of(inv.getArgument(0, String.class));
            return Files.exists(p) ? p : null;
        });

        KbAnswerWavCacheService cache = new KbAnswerWavCacheService(mapper, qaService, playback, props);

        assertEquals(wav.toString(), cache.resolveByQaId(7));
        assertEquals(wav.toString(), cache.resolveByQaId(7));
        verify(mapper, times(1)).selectById(7);
    }

    @Test
    void negativeCacheAvoidsRepeatDbLookup() {
        var mapper = mock(com.aicall.mapper.DialogTrainingQaMapper.class);
        var qaService = mock(DialogTrainingQaService.class);
        var playback = mock(RecordingOnlyPlaybackService.class);
        var props = new com.aicall.config.AiVoiceProperties();

        when(mapper.selectById(99)).thenReturn(null);

        KbAnswerWavCacheService cache = new KbAnswerWavCacheService(mapper, qaService, playback, props);

        assertNull(cache.resolveByQaId(99));
        assertNull(cache.resolveByQaId(99));
        verify(mapper, times(1)).selectById(99);
    }
}
