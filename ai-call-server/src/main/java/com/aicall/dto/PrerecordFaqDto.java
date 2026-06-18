package com.aicall.dto;

import lombok.Data;

@Data
public class PrerecordFaqDto {
    private Long id;
    private String questionDisplay;
    private String questionNorm;
    private String category;
    private String keywords;
    private String answerText;
    private String tier;
    private Integer hitCount;
    private Integer matchCount;
    private Integer transferCount;
    private Boolean enabled;
    private Boolean hasAnswerClip;
    /** 应答录音相对路径，如 /uploads/tts/prerecord/xxx.wav */
    private String audioUrl;
}
