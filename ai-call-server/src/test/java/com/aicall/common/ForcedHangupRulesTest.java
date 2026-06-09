package com.aicall.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ForcedHangupRulesTest {

    @Test
    void identityInquiry_bankQuestion() {
        assertTrue(ForcedHangupRules.isIdentityInquiry("有啊，你们是银行还是？"));
    }

    @Test
    void identityInquiry_companyOrPattern() {
        assertTrue(ForcedHangupRules.isIdentityInquiry("你们是什么公司"));
        assertTrue(ForcedHangupRules.isIdentityInquiry("还是机构吗"));
    }

    @Test
    void identityInquiry_notPlainAmount() {
        assertFalse(ForcedHangupRules.isIdentityInquiry("一千万"));
    }

    @Test
    void companyAddress_notIdentityInquiry() {
        assertTrue(ForcedHangupRules.isCompanyOrAddressInquiry("你们公司在哪？"));
        assertTrue(ForcedHangupRules.isCompanyOrAddressInquiry("公司在哪？"));
        assertFalse(ForcedHangupRules.isIdentityInquiry("你们公司在哪？"));
        assertFalse(ForcedHangupRules.isIdentityInquiry("公司在哪？"));
        assertTrue(ForcedHangupRules.shouldSkipSlotOverride("公司在哪？"));
    }

    @Test
    void callerIdentity_whereAreYou_notCompanyAddress() {
        assertFalse(ForcedHangupRules.isCompanyOrAddressInquiry("你是哪里"));
        assertTrue(ForcedHangupRules.isIdentityInquiry("你是哪里"));
        assertTrue(ForcedHangupRules.shouldSkipSlotOverride("你是哪里"));
    }

    @Test
    void declinesFundingNeed_afterOpeningNo() {
        String opening = "您好，我是乐数云金融业务顾问，想问下您近期有没有资金周转需求";
        assertTrue(ForcedHangupRules.declinesFundingNeed("没有。", opening));
        assertTrue(ForcedHangupRules.declinesFundingNeed("没有没有这方面需求。", opening));
    }

    @Test
    void declinesFundingNeed_notAfterAssetQuestion() {
        assertFalse(ForcedHangupRules.declinesFundingNeed("没有。", "嗯，那您名下有车吗？"));
        assertFalse(ForcedHangupRules.declinesFundingNeed("没有。", "那您名下有房吗？"));
    }

    @Test
    void declinesFundingNeed_explicitPhrases() {
        assertTrue(ForcedHangupRules.wantsNoDisturbance("没有这方面需求"));
        assertTrue(ForcedHangupRules.declinesFundingNeed("不用贷款了", "请问需要多少资金"));
    }
}
