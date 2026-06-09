package com.aicall.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("customer_group")
public class CustomerGroup {
    @TableId(type = IdType.AUTO)
    private Integer id;
    private String groupName;
    private Integer tenantId;
    private LocalDateTime createTime;
}
