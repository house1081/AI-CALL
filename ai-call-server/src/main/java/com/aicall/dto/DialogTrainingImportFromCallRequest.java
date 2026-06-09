package com.aicall.dto;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class DialogTrainingImportFromCallRequest {
    private Integer callRecordId;
    /** 1人工修正 2优质样本 3负样本 */
    private Integer dataType;
    private String question;
    /** 标准答案（人工修正后） */
    private String standardAnswer;
    /** 原 AI 回复（写入备注便于溯源） */
    private String originalAiAnswer;
    private BigDecimal weight;
    private Integer turnIndex;
}
