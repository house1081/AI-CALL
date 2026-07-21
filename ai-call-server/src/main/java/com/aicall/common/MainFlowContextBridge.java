package com.aicall.common;

import com.aicall.util.AsrTextNormalizer;
import org.springframework.util.StringUtils;

/**
 * 主线推进时把「客户刚说的话」接进下一句，避免干播固定脚本。
 */
public final class MainFlowContextBridge {

    private MainFlowContextBridge() {
    }

    /**
     * @param userText     客户本轮话
     * @param lastAssistant 上一轮 AI（多为刚问完的主线题）
     * @param nextScript   即将推进的主线原文
     */
    public static String bridge(String userText, String lastAssistant, String nextScript) {
        if (!StringUtils.hasText(nextScript)) {
            return nextScript;
        }
        String script = nextScript.trim();
        if (!StringUtils.hasText(userText) || DialogSlotHelper.isGreetingOnly(userText)
                || DialogSlotHelper.isPunctuationOnly(userText)) {
            return script;
        }
        // 脚本本身已带承接（好的/嗯/明白…）且客户只是短应 → 直接用
        if (scriptAlreadyBridged(script) && isShortAckOnly(userText)) {
            return script;
        }
        String ack = pickAck(userText, lastAssistant);
        if (!StringUtils.hasText(ack)) {
            return script;
        }
        String question = stripLeadingFiller(script);
        if (!StringUtils.hasText(question)) {
            return script;
        }
        // 避免「好的，好的，那…」
        if (question.startsWith(ack) || question.startsWith(ack.replace("，", ""))) {
            return question;
        }
        return ack + question;
    }

    /** 客户说了较具体内容时，可用 LLM 再润色一句（仍须带上主线问题） */
    public static boolean shouldLlmPolish(String userText) {
        if (!StringUtils.hasText(userText)) {
            return false;
        }
        String n = normalize(userText);
        if (n.length() < 4) {
            return false;
        }
        if (isShortAckOnly(userText) || DialogSlotHelper.isFillerOnly(userText)) {
            return false;
        }
        return true;
    }

    public static String polishSystemHint(String userText, String lastAssistant, String nextScript, int maxChars) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是电话信贷顾问，必须口语化，像真人聊天。")
                .append("客户刚说：「").append(safe(userText)).append("」。");
        if (StringUtils.hasText(lastAssistant)) {
            sb.append("你上一句问的是：「").append(safe(lastAssistant)).append("」。");
        }
        sb.append("本轮必须先半句接住客户意思，再自然问出主线问题，意思要对上：「")
                .append(safe(nextScript)).append("」。")
                .append("可用口语改写（如「请问需要多少」→「那您大概要多少」），但别改问题本意；")
                .append("只说一句口语，总长不超过").append(Math.max(24, maxChars)).append("字；")
                .append("不要复读客户原话超过4个字；不要一次问两个问题；不要告别。");
        return sb.toString();
    }

    static String pickAck(String userText, String lastAssistant) {
        String u = normalize(userText);
        String ai = lastAssistant == null ? "" : lastAssistant.trim();

        if (ForcedHangupRules.isAsrCorrectionOrRetraction(userText)) {
            return "没事，";
        }
        if (AsrTextNormalizer.isNumericAmountUtterance(userText)
                || DialogSlotHelper.isStatingLoanAmount(userText)
                || looksLikeAmount(u)) {
            return "好的" + compactAmount(userText) + "，";
        }
        if (ai.contains("有车")) {
            if (isNeg(u)) {
                return "嗯没有车，";
            }
            if (isPos(u) || u.contains("有")) {
                return "好的有车，";
            }
        }
        if (ai.contains("有房") || ai.contains("房子")) {
            if (isNeg(u)) {
                return "嗯没有房，";
            }
            if (isPos(u) || u.contains("有")) {
                return "好的有房，";
            }
        }
        if (ai.contains("社保") || ai.contains("公积金")) {
            if (isNeg(u) || u.contains("都没有") || u.contains("没交")) {
                return "嗯了解，";
            }
            if (isPos(u) || u.contains("有") || u.contains("在交") || u.contains("正常")) {
                return "好的，";
            }
            if (containsDuration(u)) {
                return "好的，";
            }
        }
        if (ai.contains("上班") || ai.contains("做生意") || ai.contains("职业")) {
            if (u.contains("上班") || u.contains("打工") || u.contains("工")) {
                return "好的上班，";
            }
            if (u.contains("生意") || u.contains("个体") || u.contains("自己干")) {
                return "好的做生意，";
            }
        }
        if (ai.contains("工资") || ai.contains("收入") || ai.contains("流水") || ai.contains("开票") || ai.contains("纳税")) {
            if (isNeg(u)) {
                return "嗯好的，";
            }
            if (looksLikeAmount(u) || containsDuration(u)) {
                return "好的，";
            }
        }
        if (ai.contains("征信") || ai.contains("逾期")) {
            if (isNeg(u) || u.contains("没有") || u.contains("正常") || u.contains("良好")) {
                return "好的，";
            }
        }
        if (ai.contains("全款") || ai.contains("按揭")) {
            if (u.contains("全款")) {
                return "好的全款，";
            }
            if (u.contains("按揭") || u.contains("贷款")) {
                return "好的按揭，";
            }
        }
        if (isNeg(u)) {
            return "嗯好的，";
        }
        if (isPos(u) || ForcedHangupRules.isCooperativeAnswer(userText)
                || DialogScriptKeywordMatcher.looksLikeAccept(userText)) {
            return "好的，";
        }
        if (u.length() <= 6) {
            return "嗯，";
        }
        return "明白，";
    }

    private static boolean scriptAlreadyBridged(String script) {
        return script.startsWith("好的") || script.startsWith("嗯") || script.startsWith("哦")
                || script.startsWith("明白") || script.startsWith("了解") || script.startsWith("没事");
    }

    private static boolean isShortAckOnly(String userText) {
        String n = normalize(userText);
        return n.equals("有") || n.equals("有啊") || n.equals("有的") || n.equals("没有") || n.equals("没")
                || n.equals("没有啊") || n.equals("嗯") || n.equals("哦") || n.equals("啊") || n.equals("好")
                || n.equals("好的") || n.equals("行") || n.equals("可以") || n.equals("对") || n.equals("是")
                || n.equals("是的") || n.equals("不用") || n.equals("不需要");
    }

    private static String stripLeadingFiller(String script) {
        String s = script.trim();
        // 去掉脚本自带的弱承接，避免双层「好的」
        s = s.replaceFirst("^(好的[，,]?|嗯[嗯]?[，,]?|哦[，,]?|明白[，,]?|了解[，,]?)+", "");
        return s.trim();
    }

    private static boolean isPos(String n) {
        return n.equals("有") || n.equals("有啊") || n.equals("有的") || n.equals("是") || n.equals("是的")
                || n.equals("对") || n.equals("好") || n.equals("好的") || n.equals("行") || n.equals("可以")
                || n.equals("正常") || n.equals("在交");
    }

    private static boolean isNeg(String n) {
        return n.equals("没有") || n.equals("没") || n.equals("没有啊") || n.equals("没有的")
                || n.equals("无") || n.equals("不") || n.equals("不是") || n.equals("不行")
                || n.equals("没有交") || n.equals("没交");
    }

    private static boolean looksLikeAmount(String n) {
        return n.matches(".*\\d+.*") || n.contains("万") || n.contains("千") || n.contains("块")
                || n.contains("元");
    }

    private static boolean containsDuration(String n) {
        return n.contains("年") || n.contains("月") || n.contains("半年") || n.contains("一年")
                || n.contains("两年") || n.contains("三年");
    }

    private static String compactAmount(String userText) {
        String n = normalize(userText);
        if (n.length() <= 6) {
            return n;
        }
        // 过长数字描述只留「万」相关短片段
        int idx = n.indexOf('万');
        if (idx >= 0) {
            int from = Math.max(0, idx - 4);
            return n.substring(from, Math.min(n.length(), idx + 1));
        }
        return "";
    }

    private static String normalize(String text) {
        return text == null ? "" : text.trim().replaceAll("[\\s，,。.!！?？~～、；;]+", "");
    }

    private static String safe(String s) {
        if (!StringUtils.hasText(s)) {
            return "";
        }
        String t = s.trim();
        return t.length() > 40 ? t.substring(0, 40) + "…" : t;
    }
}
