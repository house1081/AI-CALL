package com.aicall.dto;

import com.aicall.entity.CallTask;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class CallTaskVO extends CallTask {
    private String groupName;
    /** 生效的话术模板名称 */
    private String promptName;
    /** 绑定的训练知识库 */
    private Integer kbId;
    private String kbName;
}
