package com.aicall.dto;

import lombok.Data;

@Data
public class DialogRagHitDto {
    private Integer qaId;
    private String question;
    private String standardAnswer;
    private Integer dataType;
    private String dataTypeLabel;
    private double rawScore;
    private double weightedScore;
}
