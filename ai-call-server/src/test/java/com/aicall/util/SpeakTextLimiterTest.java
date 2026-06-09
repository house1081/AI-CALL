package com.aicall.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpeakTextLimiterTest {

    private static final int MAX = 36;

    @Test
    void shortSentenceUnchanged() {
        String s = SpeakTextLimiter.limit("好的，明天要用的话方便说下额度吗？", MAX);
        assertTrue(s.length() <= MAX + 6);
        assertTrue(s.endsWith("？") || s.endsWith("。"));
    }

    @Test
    void longSingleSentenceTruncatesAtClauseWithCompleteEnding() {
        String raw = "如果您这边有信用卡使用半年以上，或者支付宝的芝麻信用分620分以上"
                + "或者说您微信里面有微粒贷也是可以帮您操作贷款的哦！";
        String s = SpeakTextLimiter.limit(raw, MAX);
        assertTrue(s.length() <= MAX + 6, "actual len=" + s.length() + " text=" + s);
        assertTrue(s.endsWith("。") || s.endsWith("！") || s.endsWith("？"), s);
        assertFalse(s.contains("微粒贷"), "应截在前段分句，不应拖到后半句");
    }

    @Test
    void twoSentencesKeepsFirstOnly() {
        String raw = "我们是助贷公司的客户经理。具体额度要看您的征信和收入情况，您大概想贷多少万呢？";
        String s = SpeakTextLimiter.limit(raw, MAX);
        assertTrue(s.startsWith("我们是助贷"));
        assertTrue(s.endsWith("。") || s.endsWith("？"), s);
        assertTrue(s.length() <= MAX + 6);
    }

    @Test
    void noPunctuationGetsSentenceEnd() {
        String s = SpeakTextLimiter.limit("在听的您接着说", MAX);
        assertTrue(s.endsWith("。"), s);
    }
}
