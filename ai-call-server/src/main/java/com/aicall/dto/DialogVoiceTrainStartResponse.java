package com.aicall.dto;

import lombok.Data;

@Data
public class DialogVoiceTrainStartResponse {
    private String sessionId;
    private Integer simCallRecordId;
    private int kbId;
    /** smart_prerecord | ai_realtime */
    private String mode;
    private String provider;
    private String model;
    private String openingText;
    private String openingAudioUrl;
    private Long openingLatencyMs;
}
