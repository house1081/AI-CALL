package com.aicall.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("tenant_agent")
public class TenantAgent {
    @TableId(type = IdType.AUTO)
    private Integer id;
    private Integer tenantId;
    private String name;
    /** ext | mobile */
    private String destType;
    /** 分机完整目标或手机号 */
    private String fsDest;
    private String mobile;
    private Integer lineId;
    /** 0停用 1启用 */
    private Integer status;
    /** offline | available | busy */
    private String presence;
    private Integer sortOrder;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
