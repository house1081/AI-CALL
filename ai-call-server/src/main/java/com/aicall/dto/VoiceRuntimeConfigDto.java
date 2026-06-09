package com.aicall.dto;

import lombok.Data;

@Data
public class VoiceRuntimeConfigDto {
    /** stable=稳健(800ms) | fast=极速(680ms) */
    private String silenceProfile;
    /** 只读：当前档位生效的句末静默 ms */
    private Integer effectiveUserSilenceMs;
    /** 只读：当前档位 VAD threshold */
    private Double effectiveVadThreshold;
    /** CosyVoice 复刻 voice_id */
    private String cosyvoiceCloneVoiceId;
    /** 只读：yml 默认 CosyVoice 复刻 ID（库为空时展示） */
    private String defaultCosyvoiceCloneVoiceId;
    /** 接通后是否播报开场白 */
    private Boolean playOpeningOnAnswer;
    /** 只读：当前是否已配置 DashScope Key */
    private Boolean dashScopeConfigured;
    /** 只读：当前 CosyVoice 合成模型（须与复刻 target_model 一致） */
    private String cosyvoiceTtsModel;
    /** 只读：用户句末静默门槛 ms */
    private Integer userSilenceBeforeResponseMs;
    /** 只读：播后尾音 ms */
    private Integer turnBasedPlaybackAsrTailMs;
    /** 只读：播音期插嘴能量阈值 */
    private Integer playbackBargeInEnergyThreshold;
}
