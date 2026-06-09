package com.aicall.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("ai_prompt")
public class AiPrompt {
    @TableId(type = IdType.AUTO)
    private Integer id;
    private String promptName;
    private String promptContent;
    private String openingRemarks;
    private String endRemarks;
    private Integer kbId;
    private Integer isActive;
    private LocalDateTime updateTime;
}
