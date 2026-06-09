package com.aicall.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("customer")
public class Customer {
    @TableId(type = IdType.AUTO)
    private Integer id;
    private String phone;
    private String name;
    private String province;
    private Integer groupId;
    private String groupName;
    private String level;
    private Integer isBlack;
    private Integer tenantId;
    private LocalDateTime createTime;
    private LocalDateTime lastCallTime;
}
