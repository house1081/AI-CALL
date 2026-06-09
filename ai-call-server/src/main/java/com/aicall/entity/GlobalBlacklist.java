package com.aicall.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("global_blacklist")
public class GlobalBlacklist {
    @TableId(type = IdType.AUTO)
    private Integer id;
    private String phone;
    private LocalDateTime createTime;
}
