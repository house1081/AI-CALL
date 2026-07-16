package com.aicall.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DialogTrainingIntentRouterTest {

    @Test
    void routesPeerToHangupFaq() {
        DialogTrainingIntentRouter.IntentRoute route =
                DialogTrainingIntentRouter.resolveFaqRoute("我也是同行做贷款的", "", "04");
        assertNotNull(route);
        assertEquals(41, route.faqFallbackNo());
        assertTrue(route.hangup());
    }

    @Test
    void routesRefinanceInquiry() {
        DialogTrainingIntentRouter.IntentRoute route =
                DialogTrainingIntentRouter.resolveFaqRoute("那怎么转贷呢", "", "12");
        assertNotNull(route);
        assertEquals(135, route.faqFallbackNo());
    }

    @Test
    void routesHowToApplyNotNoNeed() {
        DialogTrainingIntentRouter.IntentRoute route =
                DialogTrainingIntentRouter.resolveFaqRoute("我需要怎么办理", "", "01");
        assertNotNull(route);
        assertEquals(17, route.faqFallbackNo());
        assertTrue(DialogTrainingIntentRouter.shouldSuppressNoNeedFaq("我需要怎么办理", 70));
    }

    @Test
    void routesOverdueNotMortgageTail() {
        DialogTrainingIntentRouter.IntentRoute route =
                DialogTrainingIntentRouter.resolveFaqRoute("我信用卡有逾期", "", "06");
        assertNotNull(route);
        assertEquals(93, route.faqFallbackNo());
        assertTrue(DialogTrainingIntentRouter.shouldSuppressMortgageTailFaq("信用卡逾期", 73));
    }

    @Test
    void routesBankAgencyQuestion() {
        DialogTrainingIntentRouter.IntentRoute route =
                DialogTrainingIntentRouter.resolveFaqRoute("你是哪个银行的", "", "01");
        assertNotNull(route);
        assertEquals(18, route.faqFallbackNo());
    }

    @Test
    void routesPhoneNumberSource() {
        DialogTrainingIntentRouter.IntentRoute route =
                DialogTrainingIntentRouter.resolveFaqRoute("你在哪里找的我的号码", "", "01");
        assertNotNull(route);
        assertEquals(38, route.faqFallbackNo());
    }

    @Test
    void routesPropertyCanApply() {
        DialogTrainingIntentRouter.IntentRoute route =
                DialogTrainingIntentRouter.resolveFaqRoute("全款房可以办吗", "", "01");
        assertNotNull(route);
        assertEquals(147, route.faqFallbackNo());
        assertTrue(DialogTrainingIntentRouter.isPropertyProductInquiry("全款房可以办吗"));
    }

    @Test
    void routesMaterialsInquiry() {
        DialogTrainingIntentRouter.IntentRoute route =
                DialogTrainingIntentRouter.resolveFaqRoute("需要哪些材料", "", "03");
        assertNotNull(route);
        assertEquals(126, route.faqFallbackNo());
    }

    @Test
    void routesOnlineLoansLateStageWithoutRepeat() {
        DialogTrainingIntentRouter.IntentRoute route =
                DialogTrainingIntentRouter.resolveFaqRoute("申请过很多网贷", "", "15");
        assertNotNull(route);
        assertEquals(149, route.faqFallbackNo());
    }

    @Test
    void routesSocialPayrollOnly() {
        DialogTrainingIntentRouter.IntentRoute route =
                DialogTrainingIntentRouter.resolveFaqRoute("只有社保打卡工资可以贷吗", "", "14");
        assertNotNull(route);
        assertEquals(148, route.faqFallbackNo());
    }

    @Test
    void affirmsPropertyOwnership() {
        assertTrue(DialogTrainingIntentRouter.affirmsPropertyOwnership("我有房"));
        assertFalse(DialogTrainingIntentRouter.affirmsPropertyOwnership("我名下有房子可以办吗"));
    }

    @Test
    void deferKeywordFaq_whenAnsweringMainFlowSlot() {
        String lastAi = "那我这边给您匹配下产品，请问您是上班还是做生意呢？";
        assertTrue(DialogTrainingIntentRouter.shouldDeferKeywordFaqToMainFlow("说错了", lastAi, "03"));
        assertTrue(DialogTrainingIntentRouter.shouldDeferKeywordFaqToMainFlow("没有", "那您名下有车吗？", "12"));
        assertFalse(DialogTrainingIntentRouter.shouldDeferKeywordFaqToMainFlow(
                "利息多少", "请问需要多少资金", "02"));
        assertFalse(DialogTrainingIntentRouter.shouldDeferKeywordFaqToMainFlow(
                "你们是哪个银行", lastAi, "03"));
    }
}
