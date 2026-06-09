package com.aicall.dto;

import com.aicall.entity.AiPrompt;
import lombok.Data;

/** 单通外呼解析后的话术上下文：模板 + 知识库 */
@Data
public class DialogCallContext {
    private Integer callRecordId;
    private Integer tenantId;
    private Integer taskId;
    private Integer promptId;
    private Integer kbId;
    private AiPrompt prompt;

    public boolean hasKb() {
        return kbId != null && kbId > 0;
    }
}
