package com.aicall.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("recharge_order")
public class RechargeOrder {
    @TableId(type = IdType.AUTO)
    private Integer id;
    private String orderNo;
    private Integer tenantId;
    private BigDecimal rechargeAmount;
    private BigDecimal arrivalBalance;
    private Integer status;
    private String voucherUrl;
    private String failReason;
    private LocalDateTime rechargeTime;
    private LocalDateTime auditTime;
}
