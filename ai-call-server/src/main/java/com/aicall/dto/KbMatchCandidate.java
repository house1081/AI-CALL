package com.aicall.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 智能预录：统一 FAQ 匹配候选（关键词 / 向量 / 录音可播性）。
 */
@Data
@Builder
public class KbMatchCandidate {
    private String replyText;
    private Integer qaId;
    private Integer keywordRuleId;
    private String wavPath;
    private boolean playable;
    /** keyword | vector | keyword-fallback-wav */
    private String source;
    private double score;
    private boolean keywordMatched;

    public boolean hasReplyText() {
        return replyText != null && !replyText.isBlank();
    }
}
