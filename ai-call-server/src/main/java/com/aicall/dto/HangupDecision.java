package com.aicall.dto;

import com.aicall.common.ForcedHangupRules;
import lombok.Data;

@Data
public class HangupDecision {
    private boolean shouldHangup;
    private String hangupType;
    private String endWords;
    private boolean aiTriggered;
    private int elapsedSeconds;
    private int invalidChatRounds;

    public static HangupDecision none(int elapsedSeconds, int invalidChatRounds) {
        HangupDecision d = new HangupDecision();
        d.setShouldHangup(false);
        d.setElapsedSeconds(elapsedSeconds);
        d.setInvalidChatRounds(invalidChatRounds);
        return d;
    }

    public static HangupDecision force(String hangupType, boolean aiTriggered, int elapsedSeconds, int invalidChatRounds) {
        HangupDecision d = new HangupDecision();
        d.setShouldHangup(true);
        d.setHangupType(hangupType);
        d.setEndWords(ForcedHangupRules.endWordsFor(hangupType));
        d.setAiTriggered(aiTriggered);
        d.setElapsedSeconds(elapsedSeconds);
        d.setInvalidChatRounds(invalidChatRounds);
        return d;
    }
}
