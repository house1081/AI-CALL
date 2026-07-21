package com.aicall.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LlmStreamingSentenceBufferTest {

    @Test
    void earlyPunctuationEmitsAtTenChars() {
        LlmStreamingSentenceBuffer buf = new LlmStreamingSentenceBuffer(36, 12);
        List<String> out = buf.feed("好的我这边已经记下了，");
        assertFalse(out.isEmpty(), "expected early emit");
        assertTrue(out.get(0).length() >= 6);
    }

    @Test
    void weakAckAloneDoesNotEmit() {
        LlmStreamingSentenceBuffer buf = new LlmStreamingSentenceBuffer(36, 12);
        assertTrue(buf.feed("行。").isEmpty());
        assertTrue(buf.feed("好的。").isEmpty());
    }

    @Test
    void weakAckThenQuestionEmitsTogether() {
        LlmStreamingSentenceBuffer buf = new LlmStreamingSentenceBuffer(36, 12);
        assertTrue(buf.feed("行。").isEmpty());
        List<String> out = buf.feed("明白，大概二十万，什么时候用？");
        assertFalse(out.isEmpty());
        assertTrue(out.get(0).contains("二十万") || out.get(0).length() >= 6);
    }

    @Test
    void firstChunkByCharThreshold() {
        LlmStreamingSentenceBuffer buf = new LlmStreamingSentenceBuffer(36, 12);
        assertTrue(buf.feed("一二三四五六七八九").isEmpty());
        List<String> out = buf.feed("十十一十二");
        assertFalse(out.isEmpty());
        assertTrue(out.get(0).length() >= 12);
    }

    @Test
    void isSpeakableFirstChunk_rejectsWeakAck() {
        assertFalse(LlmStreamingSentenceBuffer.isSpeakableFirstChunk("行。"));
        assertFalse(LlmStreamingSentenceBuffer.isSpeakableFirstChunk("好的。"));
        assertTrue(LlmStreamingSentenceBuffer.isSpeakableFirstChunk("明白，大概二十万，"));
    }
}
