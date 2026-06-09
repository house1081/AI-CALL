package com.aicall.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("call_record")
public class CallRecord {
    @TableId(type = IdType.AUTO)
    private Integer id;
    private Integer tenantId;
    private Integer lineId;
    private Integer customerId;
    private String customerPhone;
    private Integer callDuration;
    private Integer billedMinutes;
    /** 通话过程中已实时预扣金额 */
    private BigDecimal prepaidAmount;
    private BigDecimal sellPriceSnapshot;
    private BigDecimal costPriceSnapshot;
    private BigDecimal deductAmount;
    private BigDecimal costAmount;
    private BigDecimal profit;
    private Integer profitAbnormal;
    private String recordUrl;
    private String dialogText;
    private String customerNeed;
    private String customerPain;
    private String budget;
    private String nextTime;
    private String level;
    private Integer callStatus;
    /** 正常结束 / 强制挂断-* */
    private String hangupType;
    private LocalDateTime callTime;
    private Integer taskId;
    private Integer promptId;
    private Integer kbId;
}
