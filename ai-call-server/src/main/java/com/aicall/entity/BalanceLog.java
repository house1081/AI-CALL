package com.aicall.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("balance_log")
public class BalanceLog {
    @TableId(type = IdType.AUTO)
    private Integer id;
    private Integer tenantId;
    private Integer type;
    private BigDecimal amount;
    private BigDecimal balanceAfter;
    private String remark;
    private Integer refId;
    private LocalDateTime createTime;
}
