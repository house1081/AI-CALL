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
    /** 技术退出原因：customer_hangup_late / ai_hangup / max_rounds 等 */
    private String exitReason;
    /** 对话轮次 */
    private Integer dialogRounds;
    /** 是否 AI 主动挂机 */
    private Integer aiHangup;
    private LocalDateTime callTime;
    private Integer taskId;
    private Integer promptId;
    private Integer kbId;
    /** 坐席 ID（点呼 / 转人工） */
    private Integer agentId;
    /** ai_task | click_call | human_transfer */
    private String callMode;
}
