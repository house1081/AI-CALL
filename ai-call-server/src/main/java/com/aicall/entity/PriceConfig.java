package com.aicall.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("price_config")
public class PriceConfig {
    @TableId(type = IdType.AUTO)
    private Integer id;
    private Integer priceType;
    private BigDecimal defaultSellPrice;
    private LocalDateTime updateTime;
}
