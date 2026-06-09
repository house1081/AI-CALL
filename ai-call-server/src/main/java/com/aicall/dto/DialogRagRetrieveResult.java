package com.aicall.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class DialogRagRetrieveResult {
    private List<DialogRagHitDto> hits = new ArrayList<>();
    private List<DialogRagHitDto> negativeHits = new ArrayList<>();
    /** 高置信直出标准答案（跳过 LLM） */
    private boolean directAnswer;
    private String directAnswerText;
    /** 是否有可注入 Prompt 的正向匹配 */
    private boolean hasPositiveMatch;
    /** 严格模式下是否应走无匹配兜底（不调 LLM） */
    private boolean noMatchFallback;
    /** 关键词规则命中（话术包兜底库） */
    private boolean keywordMatched;
    private long retrieveMs;

    public DialogRagHitDto bestPositive() {
        return hits.isEmpty() ? null : hits.get(0);
    }
}
