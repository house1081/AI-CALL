package com.aicall.dto;

import lombok.Data;

@Data
public class CosyVoiceVoiceItemDto {
    private String voiceId;
    private String status;
    private String gmtCreate;
    /** 是否与当前 tts-model 匹配，可用于合成 */
    private Boolean compatible;
}
