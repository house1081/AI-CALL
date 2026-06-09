package com.aicall.dto;

import lombok.Data;

/**
 * 固定话术（开场白/结束语）预录音状态，供管理端展示与手动预生成结果。
 */
@Data
public class FixedVoicePrecacheStatusDto {

    private Boolean openingReady;
    private Boolean endingReady;

    private Boolean precacheEnabled;

    private String ttsModel;
    private String ttsVoice;
    private String voiceSignature;

    private Integer activePromptId;
    private String openingRemarks;
    private String endRemarks;

    private String message;

    /** 参与预生成的 CosyVoice 音色总数 */
    private Integer totalVoiceCount;
    /** 开场白已就绪的音色数 */
    private Integer openingReadyCount;
    /** 结束语已就绪的音色数 */
    private Integer endingReadyCount;
}
