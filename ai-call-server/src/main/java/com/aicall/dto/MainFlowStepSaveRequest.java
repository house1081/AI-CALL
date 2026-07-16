package com.aicall.dto;

import lombok.Data;

@Data
public class MainFlowStepSaveRequest {
    private Integer id;
    private Integer kbId;
    private String stepCode;
    private String sceneName;
    private String script;
    private Integer flowOrder;
}
