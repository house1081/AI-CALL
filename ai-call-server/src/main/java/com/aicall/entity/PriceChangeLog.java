package com.aicall.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("price_change_log")
public class PriceChangeLog {
    @TableId(type = IdType.AUTO)
    private Integer id;
    private Integer targetType;
    private Integer targetId;
    private BigDecimal oldPrice;
    private BigDecimal newPrice;
    private String operator;
    private LocalDateTime createTime;
}
