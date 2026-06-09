package com.aicall.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("risk_config")
public class RiskConfig {
    @TableId(type = IdType.AUTO)
    private Integer id;
    private Integer callInterval;
    private Integer shortCallLimit;
    private String callStartTime;
    private String callEndTime;
    private String highComplaintArea;
    /** 1=启用客户话术敏感词监控 */
    private Integer sensitiveMonitorEnabled;
    /** 1=命中后转人工 */
    private Integer humanTransferEnabled;
    /** FS 转接目标，如 user/1001 或 sofia/gateway/default/13800138000 */
    private String humanTransferDest;
    /** 转接前对客户播报 */
    private String humanTransferPrompt;
    private LocalDateTime updateTime;
}
