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
                .replace('[', ' ').replace(']', ' ');
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
        boolean strictIdentity = ForcedHangupRules.isStrictIdentityInquiry(u);
        String questionSeg = extractQuestionSegment(u);
        KeywordRule best = null;
        int bestScore = 0;
        int bestKwLen = 0;
        for (KeywordRule rule : rules) {
            if (rule.dataType() == DialogTrainingDataType.NEGATIVE) {
                continue;
            }
            for (String kw : rule.keywords()) {
                if (kw.length() < 2) {
                    continue;
                }
                if (strictIdentity && isAddressOnlyKeyword(kw)) {
                    continue;
                }
                int score = scoreKeyword(u, questionSeg, kw);
                if (score > bestScore || (score == bestScore && score > 0 && kw.length() > bestKwLen)) {
                    best = rule;
                    bestScore = score;
                    bestKwLen = kw.length();
                }
            }
        }
        return best;
    }

    /** 优先命中问句片段（如「我征信不好 你利息多少」→ 取「你利息多少」） */
    private static String extractQuestionSegment(String u) {
        int you = u.lastIndexOf('你');
        if (you >= 0 && you < u.length() - 2) {
            return u.substring(you);
        }
        String[] parts = u.split("[，,。.!！\\s]+");
        for (int i = parts.length - 1; i >= 0; i--) {
            String p = parts[i].trim();
            if (p.contains("多少") || p.contains("怎么") || p.contains("吗") || p.contains("？")
                    || p.contains("?") || p.contains("是不是") || p.contains("有没有")) {
                return p;
            }
        }
        return u;
    }

    private static int scoreKeyword(String full, String questionSeg, String kw) {
        int score = kw.length();
        if (full.contains(kw)) {
            score += 10;
        }
        if (questionSeg.contains(kw) && isLikelyQuestionContext(full)) {
            score += 100;
        }
        if (matchesKeywordVariant(questionSeg, kw)) {
            score += 80;
        } else if (full.contains(kw)) {
            // exact substring already scored above
        } else if (matchesKeywordVariant(full, kw) && kw.length() >= 4) {
            score += 20;
        } else if (!full.contains(kw)) {
            return 0;
        }
        if (isCreditProblemStatement(full)) {
            if ("征信".equals(kw) && !full.contains("上征信") && !full.contains("不上征信")
                    && !full.contains("查征信")) {
                score -= 200;
            }
            if (kw.contains("征信差") || kw.contains("征信黑") || kw.contains("逾期")
                    || kw.contains("征信乱") || kw.contains("大数据花")) {
                score += 150;
            }
        }
        // 短关键词在非问句片段中命中时降权，减少「哪里」「征信」误触
        if (kw.length() <= 3 && !questionSeg.contains(kw) && full.contains(kw)
                && !isLikelyQuestionContext(full)) {
            score -= 90;
        }
        return score > 0 ? score : 0;
    }

    private static boolean isLikelyQuestionContext(String full) {
        return full.contains("吗") || full.contains("多少") || full.contains("怎么")
                || full.contains("什么") || full.contains("是不是") || full.contains("有没有")
                || full.contains("？") || full.contains("?");
    }

    private static boolean isAddressOnlyKeyword(String kw) {
        return kw.contains("在哪") || kw.contains("地址") || kw.contains("定位")
                || kw.contains("去哪里");
    }

    /** 口语变体：如「利息大概多少」命中规则「利息多少」 */
    private static boolean matchesKeywordVariant(String userText, String keyword) {
        if (!StringUtils.hasText(keyword) || keyword.length() < 2) {
            return false;
        }
        if (keyword.contains("利息") && userText.contains("利息")) {
            return userText.contains("多少") || userText.contains("怎么") || userText.contains("吗")
                    || keyword.length() >= 6;
        }
        if (keyword.contains("利率") && userText.contains("利率")) {
            return userText.contains("多少") || userText.contains("怎么") || userText.contains("吗")
                    || keyword.length() >= 6;
        }
        if (keyword.contains("额度") && userText.contains("额度")) {
            return userText.contains("多少") || userText.contains("怎么") || userText.contains("吗")
                    || keyword.length() >= 6;
        }
        if (keyword.contains("放款") && userText.contains("放款")) {
            return userText.contains("多少") || userText.contains("怎么") || userText.contains("吗")
                    || userText.contains("多久") || keyword.length() >= 6;
        }
        if (keyword.contains("征信") && userText.contains("征信")) {
            if (isCreditProblemStatement(userText)) {
                return keyword.contains("征信差") || keyword.contains("逾期") || keyword.contains("黑户")
                        || keyword.contains("征信乱") || keyword.contains("查询多") || keyword.contains("大数据花");
            }
            return userText.contains("上征信") || userText.contains("查征信")
                    || userText.contains("不上征信") || userText.contains("征信不好")
                    || userText.contains("征信不太") || userText.contains("征信不")
                    || userText.contains("征信黑") || userText.contains("征信花");
        }
        if (keyword.contains("居间") && (userText.contains("居间") || userText.contains("中介"))) {
            return true;
        }
        if (isHearingKeyword(keyword) && isHearingUtterance(userText)) {
            return true;
        }
        return false;
    }

    private static boolean isHearingKeyword(String keyword) {
        return keyword.contains("听到") || keyword.contains("听见") || keyword.contains("在听");
    }

    private static boolean isHearingUtterance(String userText) {
        return userText.contains("听到") || userText.contains("听见") || userText.contains("在听");
    }

    /** 客户在陈述征信有问题（非单纯问「上不上征信」） */
    public static boolean isCreditProblemStatement(String userText) {
        if (!StringUtils.hasText(userText) || !userText.contains("征信")) {
            return false;
        }
        String t = userText.trim();
        return t.contains("不好") || t.contains("不太") || t.contains("不怎么")
                || t.contains("差") || t.contains("黑") || t.contains("花")
                || t.contains("逾期") || t.contains("查询多") || t.contains("大数据");
    }

    /** 连续多轮在讲同一类顾虑（如征信不好） */
    public static boolean isSameTopicConcern(String current, String previous) {
        if (!StringUtils.hasText(current) || !StringUtils.hasText(previous)) {
            return false;
        }
        if (isCreditProblemStatement(current) && isCreditProblemStatement(previous)) {
            return true;
        }
        String a = normalizeTopicKey(current);
        String b = normalizeTopicKey(previous);
        return a.length() >= 4 && a.equals(b);
    }

    private static String normalizeTopicKey(String text) {
        return text.trim()
                .replaceAll("[\\s，,。.!！?？~～、；;]+", "")
                .toLowerCase(Locale.ROOT);
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
        if (ForcedHangupRules.declinesWeChatInvitationOnly(userText, lastAssistantText)) {
            return false;
        }
        return looksLikeRefuse(userText) || ForcedHangupRules.declinesFundingNeed(userText, lastAssistantText);
    }

    public static boolean looksLikeAccept(String userText) {
        if (!StringUtils.hasText(userText)) {
            return false;
        }
        if (looksLikeRefuse(userText)) {
            return false;
        }
        String u = userText.trim();
        if (u.contains("不好") || u.contains("不行") || u.contains("不可以") || u.contains("不用")
                || u.contains("不要") || u.contains("没兴趣")) {
            return false;
        }
        return ForcedHangupRules.acceptsOpeningFundingIntent(userText)
                || ForcedHangupRules.isCooperativeAnswer(userText) || ForcedHangupRules.hasBusinessIntent(userText)
                || u.contains("可以") || u.equals("行") || u.equals("好") || u.equals("嗯")
                || u.contains("想了解") || u.contains("了解一下") || u.contains("有兴趣");
    }
}
