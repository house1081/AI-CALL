package com.aicall.common;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DialogScriptKeywordMatcherTest {

    private static final DialogScriptKeywordMatcher.KeywordRule HEARING_RULE =
            new DialogScriptKeywordMatcher.KeywordRule(
                    42,
                    List.of("听得到吗", "能听到吗", "听得见吗", "在听吗", "讲话能听到", "能听见吗"),
                    "在听的，您接着说",
                    DialogTrainingDataType.MANUAL_CORRECTION);

    @Test
    void matchesHearingCheckVariants() {
        for (String user : List.of("听得到吗", "讲话能听到吗", "能听到吗", "听得见吗", "在听吗")) {
            DialogScriptKeywordMatcher.KeywordRule hit =
                    DialogScriptKeywordMatcher.match(user, List.of(HEARING_RULE));
            assertNotNull(hit, "应命中听感确认: " + user);
            assertEquals("在听的，您接着说", hit.answer());
        }
    }

    @Test
    void shortKeywordNotMatchedOutsideQuestionContext() {
        DialogScriptKeywordMatcher.KeywordRule whereRule =
                new DialogScriptKeywordMatcher.KeywordRule(
                        18,
                        List.of("哪里"),
                        "我们是线上平台",
                        DialogTrainingDataType.MANUAL_CORRECTION);
        assertNull(DialogScriptKeywordMatcher.match("嗯可以", List.of(whereRule)));
    }

    @Test
    void creditProblemMatchesBadCreditFaqNotGenericZhengxin() {
        DialogScriptKeywordMatcher.KeywordRule generic =
                new DialogScriptKeywordMatcher.KeywordRule(
                        2,
                        List.of("上不上征信", "征信"),
                        "这个要看具体情况，每个产品不一样，有的会上征信",
                        DialogTrainingDataType.MANUAL_CORRECTION);
        DialogScriptKeywordMatcher.KeywordRule badCredit =
                new DialogScriptKeywordMatcher.KeywordRule(
                        93,
                        List.of("征信差", "黑户", "逾期"),
                        "目前银行有三百多种产品，不管征信好坏，都能找到适合的贷款产品",
                        DialogTrainingDataType.MANUAL_CORRECTION);
        DialogScriptKeywordMatcher.KeywordRule hit =
                DialogScriptKeywordMatcher.match("我这边征信不太好", List.of(generic, badCredit));
        assertNotNull(hit);
        assertEquals(93, hit.id());
        hit = DialogScriptKeywordMatcher.match("现在征信就不怎么好", List.of(generic, badCredit));
        assertNotNull(hit);
        assertEquals(93, hit.id());
    }

    @Test
    void sameTopicConcernDetectsRepeatedCreditWorry() {
        assertTrue(DialogScriptKeywordMatcher.isSameTopicConcern(
                "我这边征信不太好", "现在征信就不怎么好"));
        assertTrue(DialogScriptKeywordMatcher.isCreditProblemStatement("我现在的征信不太好"));
    }
}
