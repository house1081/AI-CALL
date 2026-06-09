package com.aicall.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("dialog_knowledge_base")
public class DialogKnowledgeBase {
    @TableId(type = IdType.AUTO)
    private Integer id;
    private String kbName;
    private String description;
    /** NULL=平台公共库 */
    private Integer tenantId;
    /** custom | loan */
    private String packType;
    private Integer status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
