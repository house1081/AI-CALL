package com.aicall.service;

import com.aicall.common.DialogSlotHelper;
import com.aicall.common.ForcedHangupRules;
import com.aicall.dto.AiChatMessage;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 根据对话内容规则判定意向等级（A/B/C/D），用于 LLM 提取失败或偏保守时的兜底与校正。
 */
@Service
public class IntentLevelService {

    public String inferFromDialog(String dialogText, int callDurationSec) {
        List<AiChatMessage> history = parseDialog(dialogText);
        String userBlob = collectUserText(history);
        if (!StringUtils.hasText(userBlob)) {
            return "D";
        }
        if (ForcedHangupRules.wantsNoDisturbance(userBlob)) {
            return "D";
        }
        DialogSlotHelper.Slots slots = DialogSlotHelper.extract(history, lastUserLine(history));
        if (slots.needConfirmed && slots.hasAmount && (slots.hasTime || slots.hasPurpose)) {
            return "A";
        }
        if (slots.needConfirmed && slots.hasAmount) {
            return "A";
        }
        if (slots.needConfirmed && (slots.hasTime || slots.hasPurpose)) {
            return "B";
        }
        if (slots.needConfirmed || slots.hasAmount) {
            return "B";
        }
        if (mentionsFundingNeed(userBlob) || ForcedHangupRules.hasBusinessIntent(userBlob)) {
            return callDurationSec >= 20 ? "B" : "C";
        }
        if (callDurationSec >= 45 && countMeaningfulUserLines(history) >= 1) {
            return "C";
        }
        return "D";
    }

    /** 取 LLM 与规则结果中较高意向（A &gt; B &gt; C &gt; D） */
    public String mergeLevel(String llmLevel, String ruleLevel) {
        return gradeValue(llmLevel) >= gradeValue(ruleLevel) ? normalize(llmLevel) : normalize(ruleLevel);
    }

    public String normalize(String level) {
        if (!StringUtils.hasText(level)) {
            return "D";
        }
        String s = level.trim().toUpperCase();
        if (s.length() == 1 && "ABCD".contains(s)) {
            return s;
        }
        for (char c : new char[]{'A', 'B', 'C', 'D'}) {
            if (s.indexOf(c) >= 0) {
                return String.valueOf(c);
            }
        }
        return "D";
    }

    private static int gradeValue(String level) {
        return switch (normalizeStatic(level)) {
            case "A" -> 4;
            case "B" -> 3;
            case "C" -> 2;
            default -> 1;
        };
    }

    private static String normalizeStatic(String level) {
        if (!StringUtils.hasText(level)) {
            return "D";
        }
        String s = level.trim().toUpperCase();
        return s.length() == 1 && "ABCD".contains(s) ? s : "D";
    }

    private static boolean mentionsFundingNeed(String text) {
        if (!StringUtils.hasText(text)) {
            return false;
        }
        return text.contains("需要钱") || text.contains("要钱") || text.contains("用款")
                || text.contains("想贷") || text.contains("要贷") || text.contains("贷款")
                || text.contains("借款") || text.contains("周转") || text.contains("资金")
                || text.contains("融资") || text.contains("额度");
    }

    private static int countMeaningfulUserLines(List<AiChatMessage> history) {
        int n = 0;
        for (AiChatMessage m : history) {
            if (m == null || !"user".equalsIgnoreCase(m.getRole())) {
                continue;
            }
            String c = m.getContent();
            if (!StringUtils.hasText(c)) {
                continue;
            }
            String t = c.trim();
            if (t.length() >= 2 && !t.equals("嗯") && !t.equals("啊") && !t.equals("哦")) {
                n++;
            }
        }
        return n;
    }

    private static String lastUserLine(List<AiChatMessage> history) {
        if (history == null) {
            return "";
        }
        for (int i = history.size() - 1; i >= 0; i--) {
            AiChatMessage m = history.get(i);
            if (m != null && "user".equalsIgnoreCase(m.getRole()) && StringUtils.hasText(m.getContent())) {
                return m.getContent();
            }
        }
        return "";
    }

    private static String collectUserText(List<AiChatMessage> history) {
        StringBuilder sb = new StringBuilder();
        if (history == null) {
            return "";
        }
        for (AiChatMessage m : history) {
            if (m != null && "user".equalsIgnoreCase(m.getRole()) && StringUtils.hasText(m.getContent())) {
                sb.append(m.getContent()).append(' ');
            }
        }
        return sb.toString().trim();
    }

    static List<AiChatMessage> parseDialog(String dialogText) {
        List<AiChatMessage> list = new ArrayList<>();
        if (!StringUtils.hasText(dialogText)) {
            return list;
        }
        for (String line : dialogText.split("\n")) {
            String t = line.trim();
            if (!StringUtils.hasText(t)) {
                continue;
            }
            AiChatMessage m = new AiChatMessage();
            if (t.startsWith("【客户】")) {
                m.setRole("user");
                m.setContent(t.substring(4).trim());
            } else if (t.startsWith("【AI】")) {
                m.setRole("assistant");
                m.setContent(t.substring(4).trim());
            } else {
                continue;
            }
            if (StringUtils.hasText(m.getContent())) {
                list.add(m);
            }
        }
        return list;
    }
}
