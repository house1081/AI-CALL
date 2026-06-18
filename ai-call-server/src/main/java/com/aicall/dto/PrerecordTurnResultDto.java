package com.aicall.dto;

import lombok.Data;

@Data
public class PrerecordTurnResultDto {
    private boolean handled;
    private boolean transferred;
    private boolean shouldHangup;
    private String replyText;
    private String model;
    private boolean playbackWaitHandled;
}
