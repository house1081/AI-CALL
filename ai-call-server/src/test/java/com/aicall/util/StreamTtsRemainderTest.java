package com.aicall.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StreamTtsRemainderTest {

    @Test
    void unplayed_exactPrefix() {
        assertEquals("那您大概要多少资金呢？",
                StreamTtsRemainder.unplayed("好的，那您大概要多少资金呢？", "好的，"));
    }

    @Test
    void canContinue_whenAligned() {
        assertTrue(StreamTtsRemainder.canContinueFromPrefix("好的有车，那您名下有房吗？", "好的有车，"));
        assertEquals("那您名下有房吗？",
                StreamTtsRemainder.unplayed("好的有车，那您名下有房吗？", "好的有车，"));
    }

    @Test
    void canContinue_falseWhenPolishedDiverges() {
        assertFalse(StreamTtsRemainder.canContinueFromPrefix(
                "明白，那您大概要多少资金呢？",
                "好的了解一下您这边需求"));
        assertEquals("明白，那您大概要多少资金呢？",
                StreamTtsRemainder.unplayed("明白，那您大概要多少资金呢？", "好的了解一下您这边需求"));
    }

    @Test
    void unplayed_fullWhenPrefixEmpty() {
        assertEquals("那您大概要多少资金呢？",
                StreamTtsRemainder.unplayed("那您大概要多少资金呢？", ""));
    }
}
