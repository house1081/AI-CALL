package com.aicall.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("line")
public class Line {
    @TableId(type = IdType.AUTO)
    private Integer id;
    private String sipAccount;
    private String sipPassword;
    private String sipAddress;
    private BigDecimal costPrice;
    private Integer status;
    private Integer dailyCallLimit;
    private Integer currentConcurrent;
    private Integer todayCallCount;
    private LocalDateTime createTime;
}
