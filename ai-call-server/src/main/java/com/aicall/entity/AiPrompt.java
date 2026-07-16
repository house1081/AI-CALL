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
    /** 智能预录：开场白真人录音路径 */
    private String openingWavPath;
    /** 智能预录：结束语真人录音路径 */
    private String endingWavPath;
    private Integer kbId;
    private Integer isActive;
    private LocalDateTime updateTime;
}
