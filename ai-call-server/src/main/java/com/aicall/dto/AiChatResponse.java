package com.aicall.dto;

import lombok.Data;

@Data
public class AiChatResponse {
    private String reply;
    private String model;
    private Long latencyMs;
    private Boolean fromOpening;
    private Boolean shouldHangup;
    private String hangupType;
    private String endWords;
    private Boolean hangupTriggered;
    private Boolean businessProbeNext;
    private Integer elapsedSeconds;
    private Integer invalidChatRounds;
    /** 本轮已在 LLM 流式过程中按句播报 TTS */
    private Boolean streamedTtsPlayed;
    /** 流式首句+补播已在 voiceTurn 内等待播完，对话循环无需再 waitPlayback */
    private Boolean playbackWaitHandled;
    /** 本轮应答来自问答音频缓存（未调 LLM） */
    private Boolean fromReplyAudioCache;
    /** 首句真正开播距本轮开始毫秒（TTFA）；未开播为 null */
    private Long ttfaMs;
    /** 本轮 RAG 检索耗时毫秒（含超时跳过） */
    private Long ragMs;
    /** 决策路径：llm / keyword / slot-mainflow / reply-cache / clarify / identity / prerecord 等 */
    private String decidePath;
}
