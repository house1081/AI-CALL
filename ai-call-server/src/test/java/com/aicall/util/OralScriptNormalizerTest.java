package com.aicall.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OralScriptNormalizerTest {

    @Test
    void removesFormalPhrases() {
        String s = OralScriptNormalizer.normalize("综上所述，额度很高，也就是说放款快。");
        assertTrue(!s.contains("综上所述"));
        assertTrue(!s.contains("也就是说"));
    }

    @Test
    void splitsSellingPointsAtClause() {
        String raw = "额度高，利息低，而且全程没有隐形收费。";
        List<String> parts = OralScriptNormalizer.splitForPlayback(raw, 36);
        assertTrue(parts.size() >= 2);
    }

    @Test
    void pauseAfterCommaShorterThanSentence() {
        int clause = OralScriptNormalizer.pauseMsAfter("额度高，", 250, 450, 400);
        int sentence = OralScriptNormalizer.pauseMsAfter("额度高。", 250, 450, 400);
        assertTrue(clause < sentence);
    }
}
