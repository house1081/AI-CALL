package com.aicall.dto;

import lombok.Data;

/** 主线流程节点（管理端配置） */
@Data
public class MainFlowStepDto {
    private Integer id;
    private Integer kbId;
    /** 节点编号，如 01、02、18 */
    private String stepCode;
    /** 场景名称，如「询问资金额度」 */
    private String sceneName;
    /** 播报文案（AI 实时 TTS / 预录对照） */
    private String script;
    private Integer flowOrder;
    private String answerWavPath;
    private String audioUrl;
    private Boolean audioReady;
}
