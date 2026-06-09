package com.aicall.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("tenant")
public class Tenant {
    @TableId(type = IdType.AUTO)
    private Integer id;
    private String username;
    private String password;
    private String contactName;
    private String contactPhone;
    private BigDecimal balance;
    private BigDecimal pendingDeduct;
    private Integer priceType;
    private BigDecimal sellPrice;
    private Integer status;
    private Integer dailyCallLimit;
    private Integer promptId;
    private LocalDateTime createTime;
    private LocalDateTime lastLoginTime;
}
