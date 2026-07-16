package com.aicall.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DialogQueryNormalizerTest {

    @Test
    void stripsLeadingFiller() {
        assertEquals("讲话能听到吗", DialogQueryNormalizer.forRetrieval("嗯，讲话能听到吗"));
    }
}
