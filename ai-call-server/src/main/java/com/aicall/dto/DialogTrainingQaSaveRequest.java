package com.aicall.dto;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class DialogTrainingQaSaveRequest {
    private Integer id;
    private Integer kbId;
    private String question;
    private String standardAnswer;
    private Integer dataType;
    private BigDecimal weight;
    private Integer status;
    private Integer sourceCallId;
    private String remark;
}
