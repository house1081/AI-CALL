package com.aicall.dto;

import lombok.Data;

@Data
public class DialogVoiceTrainTurnResponse {
    private String sessionId;
    private String userText;
    private String replyText;
    private String replyAudioUrl;
    private String model;
    private boolean handled;
    private boolean shouldHangup;
    private String hangupType;
    private Boolean businessProbeNext;
    private Integer invalidChatRounds;
    private Integer elapsedSeconds;
    private long asrMs;
    private long turnMs;
    /** ASR 失败原因：silent / asr_not_configured / no_text 等 */
    private String asrError;
}
