package com.aicall.common;

import com.aicall.dto.AiChatMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DialogSlotHelperTest {

    @Test
    void extract_yuanAmount600000() {
        DialogSlotHelper.Slots slots = DialogSlotHelper.extract(List.of(), "600000，下个月要。");
        assertTrue(slots.hasAmount);
        assertEquals("六十万", slots.amountLabel);
        assertTrue(slots.hasTime);
    }

    @Test
    void extract_loan600000InSentence() {
        DialogSlotHelper.Slots slots = DialogSlotHelper.extract(List.of(), "我说我要贷款600000，听得懂吗？");
        assertTrue(slots.hasAmount);
        assertEquals("六十万", slots.amountLabel);
    }

    @Test
    void extract_wanSuffix60() {
        DialogSlotHelper.Slots slots = DialogSlotHelper.extract(List.of(), "贷款60万");
        assertTrue(slots.hasAmount);
        assertEquals("60万", slots.amountLabel);
    }

    @Test
    void isStatingLoanAmount_bareDigits() {
        assertTrue(DialogSlotHelper.isStatingLoanAmount("600000。"));
        assertTrue(DialogSlotHelper.isStatingLoanAmount("60万"));
    }

    @Test
    void nextReply_afterAmountCorrection() {
        AiChatMessage ai = new AiChatMessage();
        ai.setRole("assistant");
        ai.setContent("那您这边大概想贷多少万呢？");
        AiChatMessage user = new AiChatMessage();
        user.setRole("user");
        user.setContent("600000");
        List<AiChatMessage> history = List.of(ai, user);
        DialogSlotHelper.Slots slots = DialogSlotHelper.extract(history, "他妈的，我刚才不是说600000吗？");
        assertTrue(slots.hasAmount);
        String reply = DialogSlotHelper.nextReply(slots, "他妈的，我刚才不是说600000吗？", history);
        assertTrue(reply != null && reply.contains("六十万"));
    }

    @Test
    void greetingAndPunctuation_bypassRag() {
        assertTrue(DialogSlotHelper.isPunctuationOnly("。"));
        assertTrue(DialogSlotHelper.isGreetingOnly("喂。"));
        assertTrue(DialogSlotHelper.shouldBypassRag("喂"));
        assertFalse(DialogSlotHelper.shouldBypassRag("你们公司在哪"));
    }
}
