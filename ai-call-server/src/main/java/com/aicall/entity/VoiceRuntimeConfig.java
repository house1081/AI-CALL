package com.aicall.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("voice_runtime_config")
public class VoiceRuntimeConfig {
    @TableId(type = IdType.INPUT)
    private Integer id;
    /** stable | fast，句末静默/VAD 阈值档位 */
    private String silenceProfile;
    /** 分段模式 CosyVoice 复刻 voice_id */
    private String cosyvoiceCloneVoiceId;
    /** clone=复刻音色 | system=系统预置音色 */
    private String ttsVoiceMode;
    /** 系统预置音色 voice 参数（如 longanyang） */
    private String cosyvoiceSystemVoice;
    /** 1=接通后播报开场白（后台控制） */
    private Integer playOpeningOnAnswer;
    private LocalDateTime updateTime;
}
