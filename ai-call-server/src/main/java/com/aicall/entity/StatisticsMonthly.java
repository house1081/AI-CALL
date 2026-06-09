package com.aicall.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("statistics_monthly")
public class StatisticsMonthly {
    @TableId(type = IdType.AUTO)
    private Integer id;
    private String statMonth;
    private Integer tenantId;
    private Integer lineId;
    private Integer totalCalls;
    private Integer connectedCalls;
    private Long totalDurationSec;
    private BigDecimal totalDeduct;
    private BigDecimal totalCost;
    private BigDecimal totalProfit;
    private LocalDateTime createTime;
}
