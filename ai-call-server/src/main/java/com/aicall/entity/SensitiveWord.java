package com.aicall.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("sensitive_word")
public class SensitiveWord {
    @TableId(type = IdType.AUTO)
    private Integer id;
    /** 1=违规 2=敏感 */
    private Integer wordType;
    private String word;
    /** 1=启用 0=停用 */
    private Integer status;
    private LocalDateTime createTime;
}
