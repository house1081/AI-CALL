package com.aicall.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @Test
    void hardNoDisturbance_onlyStrongPhrases() {
        assertTrue(ForcedHangupRules.isHardNoDisturbance("别再打了"));
        assertTrue(ForcedHangupRules.isHardNoDisturbance("不要再打扰我"));
        assertFalse(ForcedHangupRules.isHardNoDisturbance("没有这方面需求"));
        assertFalse(ForcedHangupRules.isHardNoDisturbance("不用了"));
        assertFalse(ForcedHangupRules.isHardNoDisturbance("没有"));
        assertTrue(ForcedHangupRules.declinesFundingNeed("没有", "您最近有没有资金备用或者转贷的打算？"));
        assertFalse(ForcedHangupRules.isHardNoDisturbance("没有", "您最近有没有资金备用或者转贷的打算？"));
    }

    @Test
    void unclearClarifyReply_notEmpty() {
        assertTrue(ForcedHangupRules.unclearClarifyReply("那您名下有车吗？").contains("听清"));
        assertTrue(ForcedHangupRules.softDeclineRecoveryReply().contains("了解")
                || ForcedHangupRules.softDeclineRecoveryReply().contains("利息"));
    }

    @Test
    void openingFundingIntent_positiveAdvancesMainFlow() {
        String opening = "您最近有没有资金备用或者转贷的打算？";
        assertTrue(ForcedHangupRules.isOpeningFundingIntentQuestion(opening));
        assertTrue(ForcedHangupRules.acceptsOpeningFundingIntent("有这个打算"));
        assertTrue(ForcedHangupRules.acceptsOpeningFundingIntent("有打算"));
        assertFalse(ForcedHangupRules.declinesOpeningFundingIntent("有这个打算", opening));
    }

    @Test
    void openingFundingIntent_negativePlaysEnding() {
        String opening = "您最近有没有资金备用或者转贷的打算？";
        assertTrue(ForcedHangupRules.declinesOpeningFundingIntent("没这个打算", opening));
        assertTrue(ForcedHangupRules.declinesOpeningFundingIntent("没有这个打算", opening));
        assertTrue(ForcedHangupRules.declinesOpeningFundingIntent("没有", opening));
        assertTrue(ForcedHangupRules.declinesOpeningFundingIntent("不需要", opening));
        assertTrue(ForcedHangupRules.declinesOpeningFundingIntent("暂时没有", opening));
        assertTrue(ForcedHangupRules.declinesOpeningFundingIntent("现在用不上", opening));
        assertFalse(ForcedHangupRules.acceptsOpeningFundingIntent("没这个打算"));
    }

    @Test
    void sideTalk_notTreatedAsFarewellOrHardHangup() {
        assertTrue(ForcedHangupRules.isLikelySideTalk("你挂了吧"));
        assertTrue(ForcedHangupRules.isLikelySideTalk("谁打的电话"));
        assertTrue(ForcedHangupRules.isLikelySideTalk("别接"));
        assertTrue(ForcedHangupRules.isLikelySideTalk("帮我挂掉"));
        assertFalse(ForcedHangupRules.isUserFarewell("你挂了吧"));
        assertFalse(ForcedHangupRules.isUserFarewell("谁打的电话啊挂了吧"));
        assertFalse(ForcedHangupRules.isHardNoDisturbance("别接这个电话"));
        assertTrue(ForcedHangupRules.isUserFarewell("再见"));
        assertTrue(ForcedHangupRules.isUserFarewell("我挂了"));
        assertTrue(ForcedHangupRules.isUserFarewell("挂了"));
        assertTrue(ForcedHangupRules.sideTalkClarifyReply().contains("方便"));
    }

    @Test
    void disruptiveRequests_detected() {
        assertTrue(ForcedHangupRules.isDisruptiveOrAbsurdRequest("我要一个亿"));
        assertTrue(ForcedHangupRules.isDisruptiveOrAbsurdRequest("骂人"));
        assertFalse(ForcedHangupRules.isDisruptiveOrAbsurdRequest("几万块钱"));
    }

    @Test
    void defersFundingNeed_detected() {
        assertTrue(ForcedHangupRules.defersFundingNeed("下个月可能才要用资金"));
        assertFalse(ForcedHangupRules.defersFundingNeed("可以了解一下"));
    }

    @Test
    void deniesSocialInsurance_noThose() {
        assertTrue(ForcedHangupRules.deniesSocialInsuranceQualification("没有那些"));
        assertTrue(ForcedHangupRules.deniesSocialInsuranceQualification("你讲那些我都没有"));
    }

    @Test
    void standaloneNo_detected() {
        assertTrue(ForcedHangupRules.isStandaloneNo("没有"));
    }

    @Test
    void beyondKnowledgeBaseScope() {
        assertTrue(DialogSlotHelper.isBeyondKnowledgeBaseScope("你们公司在哪里"));
        assertTrue(DialogSlotHelper.isBeyondKnowledgeBaseScope("这个我不太明白什么意思"));
        assertFalse(DialogSlotHelper.isBeyondKnowledgeBaseScope("50万"));
        assertFalse(DialogSlotHelper.isBeyondKnowledgeBaseScope("嗯"));
        assertFalse(DialogSlotHelper.isBeyondKnowledgeBaseScope("有这个打算"));
    }

    @Test
    void resolvePoliteEndWords_appendsFarewellWhenMissing() {
        assertEquals(ForcedHangupRules.END_WORDS, ForcedHangupRules.resolvePoliteEndWords(null));
        assertTrue(ForcedHangupRules.resolvePoliteEndWords("好的理解").contains("再见"));
        assertEquals(ForcedHangupRules.REFUSE_END_WORDS,
                ForcedHangupRules.resolvePoliteEndWords(ForcedHangupRules.REFUSE_END_WORDS));
    }

    @Test
    void asrCorrection_detected() {
        assertTrue(ForcedHangupRules.isAsrCorrectionOrRetraction("说错了"));
        assertTrue(ForcedHangupRules.isAsrCorrectionOrRetraction("你听错了"));
        assertFalse(ForcedHangupRules.isAsrCorrectionOrRetraction("没有"));
    }

    @Test
    void openingCooperativeResponse() {
        String opening = "您最近有没有资金备用或者转贷的打算？";
        String wechatAsk = "嗯嗯，你看咋们俩先加个微信，我给你发产品介绍，你看可以么？";
        assertTrue(ForcedHangupRules.acceptsOpeningCooperativeResponse("可以啊 给我介绍介绍"));
        assertTrue(ForcedHangupRules.acceptsOpeningCooperativeResponse("想了解"));
        assertTrue(ForcedHangupRules.declinesWeChatInvitationOnly("不用了", wechatAsk));
        assertTrue(ForcedHangupRules.declinesWeChatInvitationOnly("不用", wechatAsk));
        assertFalse(ForcedHangupRules.wantsNoDisturbance("不用了", wechatAsk));
        assertFalse(ForcedHangupRules.declinesWeChatInvitationOnly("不用了", opening));
        assertFalse(DialogScriptKeywordMatcher.looksLikeRefuse("不用了", wechatAsk));
    }
}
