package com.aicall.util;

import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 外呼播报字数上限：优先保留完整句子/分句，禁止在句中硬截断。
 */
public final class SpeakTextLimiter {

    private static final Pattern SENTENCE_SPLIT = Pattern.compile("(?<=[。！？；.!?;])");
    private static final Pattern CLAUSE_SPLIT = Pattern.compile("(?<=[，,、])");

    /** 为凑满一句，允许略超 maxChars 的上限（字符数） */
    private static final int SOFT_OVERFLOW = 6;

    private SpeakTextLimiter() {
    }

    public static String limit(String text, int maxChars) {
        if (!StringUtils.hasText(text)) {
            return text != null ? text.trim() : "";
        }
        int max = Math.max(12, maxChars);
        String s = text.trim();
        if (s.length() <= max) {
            return finalizeEnding(s);
        }

        String packed = joinCompleteSentencesUpTo(s, max);
        if (StringUtils.hasText(packed)) {
            return finalizeEnding(packed);
        }

        packed = joinCompleteClausesUpTo(s, max);
        if (StringUtils.hasText(packed)) {
            return finalizeEnding(packed);
        }

        int end = firstSentenceEndIndex(s, 0);
        if (end >= 0 && end + 1 <= max + SOFT_OVERFLOW) {
            return finalizeEnding(s.substring(0, end + 1).trim());
        }

        return finalizeEnding(truncateAtLastClause(s, max));
    }

    /**
     * 将过长片段拆成若干完整句/分句，每段不超过 maxChars（不硬截断）。
     */
    public static List<String> splitWithinLimit(String text, int maxChars) {
        List<String> out = new ArrayList<>();
        if (!StringUtils.hasText(text)) {
            return out;
        }
        int max = Math.max(12, maxChars);
        String rest = text.trim();
        while (StringUtils.hasText(rest)) {
            if (rest.length() <= max) {
                out.add(finalizeEnding(rest));
                break;
            }
            String chunk = limit(rest, max);
            if (!StringUtils.hasText(chunk) || chunk.equals(rest)) {
                out.add(finalizeEnding(rest));
                break;
            }
            out.add(chunk);
            rest = rest.substring(chunk.length()).trim();
        }
        return out;
    }

    private static String joinCompleteSentencesUpTo(String text, int max) {
        List<String> units = splitSentences(text);
        if (units.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String unit : units) {
            String u = unit.trim();
            if (!StringUtils.hasText(u)) {
                continue;
            }
            if (u.length() > max + SOFT_OVERFLOW) {
                if (sb.length() == 0) {
                    return "";
                }
                break;
            }
            String candidate = sb.length() == 0 ? u : sb + u;
            if (candidate.length() > max && sb.length() > 0) {
                break;
            }
            if (candidate.length() > max + SOFT_OVERFLOW) {
                break;
            }
            sb.setLength(0);
            sb.append(candidate);
        }
        return sb.toString();
    }

    private static String joinCompleteClausesUpTo(String text, int max) {
        List<String> units = splitClauses(text);
        if (units.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < units.size(); i++) {
            String u = units.get(i).trim();
            if (!StringUtils.hasText(u)) {
                continue;
            }
            String piece = u;
            if (i < units.size() - 1 && !endsWithClauseOrSentence(u)) {
                piece = u + "，";
            }
            String candidate = sb.length() == 0 ? piece : sb + piece;
            if (candidate.length() > max && sb.length() > 0) {
                break;
            }
            if (candidate.length() > max + SOFT_OVERFLOW) {
                if (sb.length() == 0) {
                    return truncateAtLastClause(piece, max);
                }
                break;
            }
            sb.setLength(0);
            sb.append(candidate);
        }
        return sb.toString();
    }

    private static String truncateAtLastClause(String text, int max) {
        if (!StringUtils.hasText(text) || text.length() <= max) {
            return text != null ? text.trim() : "";
        }
        int end = lastClauseEndIndex(text, max);
        if (end >= max / 4) {
            return text.substring(0, end + 1).trim();
        }
        end = lastSentenceEndIndex(text, max);
        if (end >= max / 4) {
            return text.substring(0, end + 1).trim();
        }
        return text.substring(0, Math.min(max, text.length())).trim();
    }

    /** 无句末标点时补全，避免 TTS 播半句 */
    static String finalizeEnding(String text) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        String s = text.trim();
        char last = s.charAt(s.length() - 1);
        if (isSentenceEnd(last)) {
            return s;
        }
        if (isClauseEnd(last)) {
            return s.substring(0, s.length() - 1).trim() + "。";
        }
        int clause = lastClauseEndIndex(s, s.length() - 1);
        if (clause >= s.length() / 4) {
            return s.substring(0, clause).trim() + "。";
        }
        return s + "。";
    }

    private static boolean endsWithClauseOrSentence(String s) {
        if (!StringUtils.hasText(s)) {
            return false;
        }
        char last = s.charAt(s.length() - 1);
        return isSentenceEnd(last) || isClauseEnd(last);
    }

    private static List<String> splitSentences(String text) {
        List<String> list = new ArrayList<>();
        for (String part : SENTENCE_SPLIT.split(text)) {
            if (StringUtils.hasText(part)) {
                list.add(part.trim());
            }
        }
        return list;
    }

    private static List<String> splitClauses(String text) {
        List<String> list = new ArrayList<>();
        for (String part : CLAUSE_SPLIT.split(text)) {
            if (StringUtils.hasText(part)) {
                list.add(part.trim());
            }
        }
        return list;
    }

    private static int lastSentenceEndIndex(String s, int beforeInclusive) {
        int limit = Math.min(s.length() - 1, beforeInclusive);
        for (int i = limit; i >= 0; i--) {
            if (isSentenceEnd(s.charAt(i))) {
                return i;
            }
        }
        return -1;
    }

    private static int firstSentenceEndIndex(String s, int from) {
        for (int i = Math.max(0, from); i < s.length(); i++) {
            if (isSentenceEnd(s.charAt(i))) {
                return i;
            }
        }
        return -1;
    }

    private static int lastClauseEndIndex(String s, int beforeInclusive) {
        int limit = Math.min(s.length() - 1, beforeInclusive);
        for (int i = limit; i >= 0; i--) {
            if (isClauseEnd(s.charAt(i))) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isSentenceEnd(char c) {
        return c == '。' || c == '！' || c == '？' || c == '；'
                || c == '.' || c == '!' || c == '?' || c == ';';
    }

    private static boolean isClauseEnd(char c) {
        return c == '，' || c == ',' || c == '、';
    }
}
