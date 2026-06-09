package com.aicall.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("system_notice")
public class SystemNotice {
    @TableId(type = IdType.AUTO)
    private Integer id;
    private String targetRole;
    private Integer targetId;
    private String title;
    private String content;
    private Integer isRead;
    private LocalDateTime createTime;
}
