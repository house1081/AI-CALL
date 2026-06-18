package com.aicall.dto;

import lombok.Data;

/** 单通外呼缓存的通话元数据，避免每轮查 call_record */
@Data
public class CallSessionMeta {
    private Integer callRecordId;
    private Integer taskId;
    private String customerPhone;
}
