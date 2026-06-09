package com.aicall.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StreamTtsRemainderTest {

    @Test
    void prefixIsSubsetOfFull() {
        String full = "好的，明白啦～您明天要用的话方便说下额度吗？";
        String prefix = "好的，明白啦～";
        assertEquals("您明天要用的话方便说下额度吗？", StreamTtsRemainder.unplayed(full, prefix));
    }

    @Test
    void sameTextNoRemainder() {
        String s = "好的，在听的。";
        assertEquals("", StreamTtsRemainder.unplayed(s, s));
    }

    @Test
    void overlapWhenPrefixTrimmed() {
        String full = "好的，明白啦～您接着说。";
        String prefix = "好的，";
        assertEquals("明白啦～您接着说。", StreamTtsRemainder.unplayed(full, prefix));
    }
}
