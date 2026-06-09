package com.aicall.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("call_task")
public class CallTask {
    @TableId(type = IdType.AUTO)
    private Integer id;
    private String taskName;
    private Integer tenantId;
    private Integer promptId;
    private Integer groupId;
    private Integer callCount;
    private Integer completedCount;
    private Integer successCount;
    private Integer status;
    /** 1立即外呼 2定时外呼 */
    private Integer dialMode;
    private LocalDateTime scheduledStartTime;
    /** 最大振铃次数，无人接听则终止任务 */
    private Integer maxRingCount;
    private String taskRules;
    /** stable | fast | null=跟随全局 */
    private String silenceProfile;
    /** 接通后是否自动调用加微信好友接口 */
    private Integer autoAddWechat;
    /** 加 V 接口地址，如 http://192.168.60.28:7862/api/add */
    private String wechatAddApiUrl;
    /** 加好友验证语/招呼语，支持 {phone} {name} {remark} {taskName} */
    private String wechatAddMessage;
    /** 备注模板，会并入 message 或替换 {remark} */
    private String wechatAddRemark;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private LocalDateTime createTime;

    /** 保存后立即启动（仅立即外呼或定时时间已到） */
    @TableField(exist = false)
    private Boolean autoStart;
}
