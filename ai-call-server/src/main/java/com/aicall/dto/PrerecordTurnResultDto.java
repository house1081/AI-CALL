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
    /** 语音训练模式：应答录音 HTTP 地址（不经过 FreeSWITCH） */
    private String replyAudioUrl;
}
