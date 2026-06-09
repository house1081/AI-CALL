package com.aicall.common;

import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 兜底话术：ASR 文本命中 question 中的关键词（|、、，分隔） */
public final class DialogScriptKeywordMatcher {

    public record KeywordRule(int id, List<String> keywords, String answer, int dataType) {
    }

    private DialogScriptKeywordMatcher() {
    }

    public static List<String> parseKeywords(String question) {
        List<String> out = new ArrayList<>();
        if (!StringUtils.hasText(question)) {
            return out;
        }
        String q = question.trim();
        if (q.startsWith("[主线")) {
            return out;
        }
        String body = q;
        int arrow = q.indexOf('→');
        if (arrow > 0) {
            body = q.substring(0, arrow);
        }
        body = body.replace('【', ' ').replace('】', ' ')
                .replace('】', ' ').replace('[', ' ').replace(']', ' ');
        for (String part : body.split("[|、，,/；;]+")) {
            String k = part.trim();
            if (k.length() >= 2 && !k.matches("\\d+")) {
                out.add(k);
            }
        }
        return out;
    }

    public static KeywordRule match(String userText, List<KeywordRule> rules) {
        if (!StringUtils.hasText(userText) || rules == null || rules.isEmpty()) {
            return null;
        }
        String u = userText.trim();
        KeywordRule best = null;
        int bestLen = 0;
        for (KeywordRule rule : rules) {
            if (rule.dataType() == DialogTrainingDataType.NEGATIVE) {
                continue;
            }
            for (String kw : rule.keywords()) {
                if (kw.length() < 2) {
                    continue;
                }
                if (u.contains(kw) && kw.length() > bestLen) {
                    best = rule;
                    bestLen = kw.length();
                }
            }
        }
        return best;
    }

    public static boolean looksLikeRefuse(String userText) {
        if (!StringUtils.hasText(userText)) {
            return false;
        }
        String u = userText.toLowerCase(Locale.ROOT);
        return u.contains("不需要") || u.contains("不用") || u.contains("没兴趣") || u.contains("别打了")
                || u.contains("骚扰") || u.contains("挂了") || u.contains("不要") || u.contains("不考虑")
                || u.contains("没有需求") || u.contains("没需求") || u.contains("没有这方面") || u.contains("没这方面")
                || u.contains("不贷款") || u.contains("不用贷");
    }

    public static boolean looksLikeRefuse(String userText, String lastAssistantText) {
        return looksLikeRefuse(userText) || ForcedHangupRules.declinesFundingNeed(userText, lastAssistantText);
    }

    public static boolean looksLikeAccept(String userText) {
        if (!StringUtils.hasText(userText)) {
            return false;
        }
        return ForcedHangupRules.isCooperativeAnswer(userText) || ForcedHangupRules.hasBusinessIntent(userText)
                || userText.contains("可以") || userText.contains("行") || userText.contains("好") || userText.contains("嗯");
    }
}
