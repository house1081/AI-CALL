package com.aicall.dto;

import lombok.Data;

@Data
public class AiCallSummaryResponse {
    private String level;
    private String customerNeed;
    private String customerPain;
    private String budget;
    private String nextTime;
}
