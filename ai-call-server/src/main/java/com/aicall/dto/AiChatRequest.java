package com.aicall.dto;

import lombok.Data;
import java.util.ArrayList;
import java.util.List;

@Data
public class AiChatRequest {
    /** 客户当前一句话（ASR 文本） */
    private String userText;
    /** 是否首轮（返回开场白，不调模型） */
    private Boolean firstTurn;
    /** 最近对话，最多保留 3 轮（由服务端截断） */
    private List<AiChatMessage> history = new ArrayList<>();
    /** 进行中通话记录 ID（启用强制挂断规则） */
    private Integer callRecordId;
    /** 上一轮 AI 是否询问了需求/预算/痛点 */
    private Boolean businessProbeThisTurn;
    private String fsUuid;
    /** 对话训练会话 ID（无 callRecordId 时用于强制挂断计数） */
    private String trainSessionId;
}
