package com.aicall.dto;

import com.aicall.entity.CallRecord;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class CallRecordVO extends CallRecord {
    private String customerName;
}
