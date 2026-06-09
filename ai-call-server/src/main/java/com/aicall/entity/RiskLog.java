package com.aicall.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("risk_log")
public class RiskLog {
    @TableId(type = IdType.AUTO)
    private Integer id;
    private Integer lineId;
    private Integer tenantId;
    private String phone;
    private String riskType;
    private String remark;
    private LocalDateTime createTime;
}
