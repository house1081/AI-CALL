package com.aicall.dto;

import lombok.Data;

@Data
public class AiCallSummaryRequest {
    /** 整段对话文本 */
    private String dialogText;
}
