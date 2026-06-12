package com.aicall.common;

import com.aicall.dto.AiChatMessage;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 对话槽位 + 提问记录：不重复同一问法，回答不清时换语气追问，话术偏口语化。
 */
public final class DialogSlotHelper {

    private static final Pattern WAN_LONG = Pattern.compile(
            "([一二三四五六七八九十两千]{2,}|\\d{2,})\\s*万");
    private static final Pattern WAN_SHORT = Pattern.compile(
            "(?<![百])([一二三四五六789十两千\\d]+)\\s*万");
    private static final Pattern QIAN_WAN = Pattern.compile(
            "([一二三四五六789十两两\\d]+)千万");
    private static final Pattern BAI_WAN = Pattern.compile(
            "([一二三四五六789十两\\d]+)\\s*百万");
    private static final Pattern BARE_DIGIT_WAN = Pattern.compile("^(\\d{1,3})$");

    private static final String[] AMOUNT_FIRST = {
            "那您这边大概想贷多少万呢？",
            "方便说下期望额度吗，大概多少万？"
    };
    private static final String[] AMOUNT_REPHRASE = {
            "您方便再说下期望额度吗，大概多少万？",
            "没事，您大概想申请多少万的，我帮您记下来。"
    };
    private static final String[] TIME_FIRST = {
            "您打算大概什么时候用这笔钱呢？",
            "时间上急不急，大概哪段时间要用？"
    };
    private static final String[] TIME_REPHRASE = {
            "时间上我再跟您确认下，是最近就要，还是还能等等？",
            "您说下大概啥时候用，本月还是下个月都行。"
    };
    private static final String[] PURPOSE_FIRST = {
            "主要是个人周转用，还是公司经营用呢？",
            "这笔款是个人消费方面，还是生意周转呀？"
    };
    private static final String[] PURPOSE_REPHRASE = {
            "用途我再确认一句，个人用还是公司用，我好给您匹配产品。"
    };
    private static final String[] BOTH_FIRST = {
            "嗯好的，那您大概想用多少万呢？",
            "行，方便说下期望额度吗，大概多少万？"
    };
    private static final String[] BOTH_REPHRASE = {
            "分开问下，多少万、什么时候用？"
    };
    private static final String[] GOODBYE = {
            "好的，那不打扰您了，祝您生活愉快，再见。",
            "嗯嗯，感谢您时间，后续有需要随时联系，再见。"
    };
    private static final String[] WRAP_UP_FOLLOW = {
            "那我先把资料发您，您留意手机短信就行。",
            "行，我这边帮您登记好了，稍后同事会跟您联系。"
    };

    private DialogSlotHelper() {
    }

    public static class Slots {
        public boolean needConfirmed;
        public boolean hasAmount;
        public boolean hasTime;
        public boolean hasPurpose;
        public String amountLabel = "";
        public String timeLabel = "";
    }

    public static class AskState {
        public int amountAskCount;
        public int timeAskCount;
        public int purposeAskCount;
        public int bothAskCount;
        public int wrapUpCount;
        public final List<String> recentAssistant = new ArrayList<>();
    }

    public static Slots extract(List<AiChatMessage> history, String currentUser) {
        StringBuilder userBlob = new StringBuilder();
        if (StringUtils.hasText(currentUser)) {
            userBlob.append(currentUser).append(' ');
        }
        if (history != null) {
            for (AiChatMessage m : history) {
                if (m != null && "user".equalsIgnoreCase(m.getRole()) && StringUtils.hasText(m.getContent())) {
                    userBlob.append(m.getContent()).append(' ');
                }
            }
        }
        String text = userBlob.toString().trim();
        Slots s = new Slots();
        if (!StringUtils.hasText(text)) {
            return s;
        }
        s.needConfirmed = text.matches(".*(有啊|有的|有需要|需要|想贷|想借|要贷).*")
                || (text.contains("有") && !text.contains("没有") && !text.contains("没用"));
        s.hasTime = containsTimeExpr(text);
        s.timeLabel = parseTimeLabel(text);
        AmountParse ap = parseAmount(text);
        s.hasAmount = ap.found;
        s.amountLabel = ap.label;
        s.hasPurpose = text.contains("个人用") || text.contains("个人用途") || text.contains("个人住")
                || text.contains("公司经营") || text.contains("公司用") || text.contains("经营")
                || text.contains("用途") || text.contains("消费") || text.contains("周转")
                || (text.contains("个人") && !text.contains("哪个"));
        return s;
    }

    public static AskState analyzeAsks(List<AiChatMessage> history) {
        AskState st = new AskState();
        if (history == null) {
            return st;
        }
        for (AiChatMessage m : history) {
            if (m == null || !"assistant".equalsIgnoreCase(m.getRole()) || !StringUtils.hasText(m.getContent())) {
                continue;
            }
            String a = m.getContent();
            st.recentAssistant.add(a);
            if (asksAmount(a)) {
                st.amountAskCount++;
            }
            if (asksTime(a)) {
                st.timeAskCount++;
            }
            if (asksPurpose(a)) {
                st.purposeAskCount++;
            }
            if (asksBoth(a)) {
                st.bothAskCount++;
            }
            if (isWrapUpPhrase(a)) {
                st.wrapUpCount++;
            }
        }
        return st;
    }

    public static boolean isSlotsComplete(Slots slots) {
        return slots != null && slots.hasAmount && slots.hasTime && slots.hasPurpose;
    }

    /** 客户表示没有更多问题，应收尾挂断 */
    public static boolean shouldEndCall(String userText, Slots slots, List<AiChatMessage> history) {
        if (!isSlotsComplete(slots)) {
            return false;
        }
        if (isNoMoreQuestions(userText)) {
            return true;
        }
        AskState asks = analyzeAsks(history);
        return asks.wrapUpCount >= 2 && !ForcedHangupRules.isIdentityInquiry(userText)
                && !ForcedHangupRules.isServiceInquiry(userText);
    }

    public static String goodbyeReply(List<AiChatMessage> history) {
        AskState asks = analyzeAsks(history);
        return pickVariant(GOODBYE, GOODBYE, asks.wrapUpCount, false);
    }

    public static String nextReply(Slots slots, String userText, List<AiChatMessage> history) {
        if (slots == null) {
            return null;
        }
        String t = userText == null ? "" : userText.trim();
        AskState asks = analyzeAsks(history);
        String lastAi = asks.recentAssistant.isEmpty() ? "" : asks.recentAssistant.get(asks.recentAssistant.size() - 1);
        boolean unclear = isUnclearAnswer(t, slots);

        if (isAmountCorrection(t) && slots.hasAmount) {
            String ack = "抱歉刚才听错了，是" + slots.amountLabel;
            if (!slots.hasTime) {
                return ack + "，" + pickVariant(TIME_FIRST, TIME_REPHRASE, asks.timeAskCount, false);
            }
            if (!slots.hasPurpose) {
                return ack + "、" + slots.timeLabel + "要用，"
                        + pickVariant(PURPOSE_FIRST, PURPOSE_REPHRASE, asks.purposeAskCount, false);
            }
            return ack + "，我都记下了，还有别的想了解吗？";
        }
        if (t.contains("说了") || t.contains("讲过") || t.contains("刚说")) {
            return humanAck(slots) + pickMissingQuestion(slots, asks, true);
        }
        if (slots.hasAmount && (asksBoth(lastAi) || asksAmount(lastAi)) && !asksTime(lastAi)) {
            return "好的，记下了，大概 " + slots.amountLabel + "，"
                    + pickVariant(TIME_FIRST, TIME_REPHRASE, asks.timeAskCount, false);
        }
        if (isFillerOnly(t)) {
            if (isSlotsComplete(slots)) {
                return goodbyeReplyFromState(asks);
            }
            return pickMissingQuestion(slots, asks, asks.bothAskCount > 0 || asks.amountAskCount > 0);
        }
        if (unclear && !slots.hasAmount && !slots.hasTime && asks.amountAskCount + asks.timeAskCount > 0
                && !isNumericAmountUtterance(t)) {
            return pickMissingQuestion(slots, asks, true);
        }
        if (isAffirmativeNeed(t) && !slots.hasAmount && !slots.hasTime) {
            return pickVariant(BOTH_FIRST, BOTH_REPHRASE, asks.bothAskCount, false);
        }
        if (slots.hasAmount && slots.hasTime && !slots.hasPurpose) {
            return "好的，" + slots.amountLabel + "、" + slots.timeLabel + "要用，"
                    + pickVariant(PURPOSE_FIRST, PURPOSE_REPHRASE, asks.purposeAskCount, unclear);
        }
        if (slots.hasAmount && slots.hasTime && slots.hasPurpose) {
            return replyWhenSlotsComplete(slots, t, asks);
        }
        if (slots.hasTime && !slots.hasAmount) {
            if (unclear && asks.amountAskCount > 0 && !isNumericAmountUtterance(t)) {
                return "嗯嗯，" + pickVariant(AMOUNT_REPHRASE, AMOUNT_REPHRASE, asks.amountAskCount, true);
            }
            return "好的，" + slots.timeLabel + "要用的话，"
                    + pickVariant(AMOUNT_FIRST, AMOUNT_REPHRASE, asks.amountAskCount, false);
        }
        if (slots.hasAmount && slots.hasTime && !slots.hasPurpose) {
            return "好的，记下了，大概 " + slots.amountLabel + "、" + slots.timeLabel + "要用，"
                    + pickVariant(PURPOSE_FIRST, PURPOSE_REPHRASE, asks.purposeAskCount, unclear);
        }
        if (slots.hasAmount && !slots.hasTime) {
            if (unclear && asks.timeAskCount > 0) {
                return pickVariant(TIME_REPHRASE, TIME_REPHRASE, asks.timeAskCount, true);
            }
            return "明白，大概 " + slots.amountLabel + "，"
                    + pickVariant(TIME_FIRST, TIME_REPHRASE, asks.timeAskCount, false);
        }
        if (slots.needConfirmed && !slots.hasAmount && !slots.hasTime) {
            if (isAffirmativeNeed(t)) {
                return pickVariant(AMOUNT_FIRST, AMOUNT_REPHRASE, asks.amountAskCount, unclear);
            }
            return pickVariant(BOTH_FIRST, BOTH_REPHRASE, asks.bothAskCount, unclear);
        }
        if (ForcedHangupRules.isCooperativeAnswer(t)) {
            return humanize(ForcedHangupRules.continueDialogReply(t));
        }
        if (t.contains("信用贷") || t.contains("网贷") || t.contains("办过")) {
            if (isSlotsComplete(slots)) {
                return replyWhenSlotsComplete(slots, t, asks);
            }
            if (slots.hasAmount && slots.hasTime) {
                return buildWrapUpFirst(slots);
            }
            return pickMissingQuestion(slots, asks, false);
        }
        return null;
    }

    public static String promptSummary(Slots slots) {
        if (slots == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder("\n\n【对话进度】");
        sb.append(slots.needConfirmed ? "客户有资金需求；" : "");
        if (slots.hasAmount) {
            sb.append("已说额度：").append(slots.amountLabel).append("；");
        }
        if (slots.hasTime) {
            sb.append("已说时间：").append(slots.timeLabel).append("；");
        }
        if (slots.hasPurpose) {
            sb.append("已说用途；");
        }
        sb.append("禁止重复同一句话术；已问过的问题换种说法或只补问缺失项。");
        sb.append("语气像真人电话沟通：自然、简短、有礼貌，可用「嗯」「好的」「没事」等口语词，");
        sb.append("避免公文腔、避免连续使用「您需要多少资金、计划何时使用」这类套话。");
        if (slots.hasTime && !slots.hasAmount) {
            sb.append("本轮只问额度。");
        } else if (slots.hasAmount && !slots.hasTime) {
            sb.append("本轮只问使用时间。");
        } else if (slots.hasAmount && slots.hasTime && !slots.hasPurpose) {
            sb.append("本轮只问个人用还是经营用。");
        }
        return sb.toString();
    }

    /** 与近期 AI 话术高度相似则视为重复 */
    public static boolean isTooSimilarToRecent(String reply, List<AiChatMessage> history) {
        if (!StringUtils.hasText(reply) || history == null) {
            return false;
        }
        String norm = normalizeProbe(reply);
        int checked = 0;
        for (int i = history.size() - 1; i >= 0 && checked < 4; i--) {
            AiChatMessage m = history.get(i);
            if (m == null || !"assistant".equalsIgnoreCase(m.getRole()) || !StringUtils.hasText(m.getContent())) {
                continue;
            }
            checked++;
            String prev = normalizeProbe(m.getContent());
            if (norm.equals(prev)) {
                return true;
            }
            if (norm.length() >= 8 && prev.length() >= 8) {
                if (norm.contains(prev) || prev.contains(norm)) {
                    return true;
                }
                if (similarityRatio(norm, prev) >= 0.65) {
                    return true;
                }
            }
        }
        return false;
    }

    public static boolean isStaleAmountTimeProbe(String reply, String lastAssistant) {
        if (!StringUtils.hasText(reply) || !StringUtils.hasText(lastAssistant)) {
            return false;
        }
        if (!isAmountTimeProbe(reply)) {
            return false;
        }
        return normalizeProbe(reply).equals(normalizeProbe(lastAssistant));
    }

    public static boolean isAmountTimeProbe(String reply) {
        if (!StringUtils.hasText(reply)) {
            return false;
        }
        return reply.contains("多少") && (reply.contains("何时") || reply.contains("什么时候") || reply.contains("计划"));
    }

    /** LLM 回复若重复或套话，改由槽位逻辑生成 */
    public static String diversifyIfNeeded(String reply, Slots slots, String userText, List<AiChatMessage> history) {
        if (ForcedHangupRules.shouldSkipSlotOverride(userText)) {
            return StringUtils.hasText(reply) ? humanize(reply) : null;
        }
        if (isSlotsComplete(slots)) {
            if (ForcedHangupRules.isIdentityInquiry(userText)) {
                return null;
            }
            if (shouldEndCall(userText, slots, history)) {
                return goodbyeReply(history);
            }
            if (isWrapUpPhrase(reply) && analyzeAsks(history).wrapUpCount > 0) {
                String alt = replyWhenSlotsComplete(slots, userText, analyzeAsks(history));
                return alt != null ? alt : goodbyeReply(history);
            }
        }
        AskState asks = analyzeAsks(history);
        boolean bad = isTooSimilarToRecent(reply, history)
                || isStaleAmountTimeProbe(reply, asks.recentAssistant.isEmpty() ? "" : asks.recentAssistant.get(asks.recentAssistant.size() - 1))
                || (isAmountTimeProbe(reply) && (slots.hasTime || slots.hasAmount))
                || (isWrapUpPhrase(reply) && asks.wrapUpCount > 0)
                || mismatchesSlotAmount(reply, slots)
                || (ForcedHangupRules.isGenericProductIntro(reply)
                && (slots.hasAmount || slots.hasTime || slots.hasPurpose || slots.needConfirmed));
        if (!bad) {
            return humanize(reply);
        }
        String alt = nextReply(slots, userText, history);
        return alt != null ? alt : humanize(reply);
    }

    private static String replyWhenSlotsComplete(Slots slots, String userText, AskState asks) {
        if (ForcedHangupRules.isIdentityInquiry(userText)) {
            return null;
        }
        if (ForcedHangupRules.isServiceInquiry(userText)) {
            return null;
        }
        if (isNoMoreQuestions(userText)) {
            return goodbyeReplyFromState(asks);
        }
        if (asks.wrapUpCount >= 2) {
            return goodbyeReplyFromState(asks);
        }
        if (asks.wrapUpCount == 1) {
            return pickVariant(WRAP_UP_FOLLOW, GOODBYE, 0, false);
        }
        return buildWrapUpFirst(slots);
    }

    private static String buildWrapUpFirst(Slots slots) {
        StringBuilder sb = new StringBuilder("好的，");
        if (StringUtils.hasText(slots.amountLabel)) {
            sb.append(slots.amountLabel).append("、");
        }
        if (StringUtils.hasText(slots.timeLabel)) {
            sb.append(slots.timeLabel).append("要用，");
        }
        sb.append("个人这边我都记下了，稍后把申请步骤发您，还有别的想了解吗？");
        return sb.toString();
    }

    private static String goodbyeReplyFromState(AskState asks) {
        return pickVariant(GOODBYE, GOODBYE, Math.max(0, asks.wrapUpCount - 1), false);
    }

    private static boolean isNoMoreQuestions(String t) {
        if (!StringUtils.hasText(t)) {
            return false;
        }
        String n = t.replaceAll("[\\s，,。.!！?？~～]+", "");
        return n.contains("没有了") || n.contains("没有啦") || n.contains("没了") || n.equals("没有")
                || n.contains("不用了") || n.contains("就这些") || n.contains("就这样");
    }

    private static boolean isWrapUpPhrase(String a) {
        return a != null && (a.contains("记下了") || a.contains("流程发您") || a.contains("还有其他想了解"));
    }

    private static String pickMissingQuestion(Slots slots, AskState asks, boolean rephrase) {
        if (slots.hasTime && !slots.hasAmount) {
            return pickVariant(AMOUNT_FIRST, AMOUNT_REPHRASE, asks.amountAskCount, rephrase);
        }
        if (slots.hasAmount && !slots.hasTime) {
            return pickVariant(TIME_FIRST, TIME_REPHRASE, asks.timeAskCount, rephrase);
        }
        if (slots.hasAmount && slots.hasTime && !slots.hasPurpose) {
            return pickVariant(PURPOSE_FIRST, PURPOSE_REPHRASE, asks.purposeAskCount, rephrase);
        }
        return pickVariant(BOTH_FIRST, BOTH_REPHRASE, asks.bothAskCount, rephrase);
    }

    private static String humanAck(Slots slots) {
        StringBuilder sb = new StringBuilder("不好意思啊，我记一下，");
        if (slots.hasAmount) {
            sb.append(slots.amountLabel);
        }
        if (slots.hasTime) {
            if (slots.hasAmount) {
                sb.append("、");
            }
            sb.append(slots.timeLabel).append("要用");
        }
        if (slots.hasAmount || slots.hasTime) {
            sb.append("，");
        }
        return sb.toString();
    }

    private static String pickVariant(String[] first, String[] rephrase, int askedCount, boolean unclear) {
        String[] pool = (askedCount > 0 && unclear) ? rephrase : (askedCount > 0 ? rephrase : first);
        if (askedCount > 0 && !unclear) {
            pool = first;
        }
        int idx = askedCount % pool.length;
        return pool[idx];
    }

    /** 客户连续重复同一短句（如「嗯」）时的换说法提示 */
    public static String nudgeForRepeatedUserInput(List<AiChatMessage> history, String userText) {
        Slots slots = extract(history, userText);
        if (slots.hasAmount && isNumericAmountUtterance(userText)) {
            if (!slots.hasPurpose) {
                return "好的，" + slots.amountLabel + "我记下了，"
                        + pickVariant(PURPOSE_FIRST, PURPOSE_REPHRASE, analyzeAsks(history).purposeAskCount, false);
            }
            return "好的，" + slots.amountLabel + "我都记下了，还有别的想了解吗？";
        }
        if (!isFillerOnly(userText)) {
            return null;
        }
        AskState asks = analyzeAsks(history);
        if (asks.bothAskCount >= 2) {
            return pickVariant(GOODBYE, GOODBYE, asks.bothAskCount, false);
        }
        if (asks.bothAskCount > 0) {
            return pickVariant(AMOUNT_REPHRASE, TIME_REPHRASE, asks.bothAskCount, true);
        }
        return pickVariant(BOTH_REPHRASE, AMOUNT_FIRST, 0, true);
    }

    private static boolean isUnclearAnswer(String t, Slots slots) {
        if (!StringUtils.hasText(t) || isFillerOnly(t)) {
            return true;
        }
        if (isNumericAmountUtterance(t) || slots.hasAmount) {
            return false;
        }
        String n = t.replaceAll("[\\s，,。.!！?？~～]+", "");
        if (n.length() <= 3 && !slots.hasAmount && !slots.hasTime) {
            return true;
        }
        return n.equals("这个月") && !slots.hasAmount
                || n.equals("有啊") || n.equals("有") || n.equals("好的") || n.equals("好的呀");
    }

    private static boolean isNumericAmountUtterance(String t) {
        return com.aicall.util.AsrTextNormalizer.isNumericAmountUtterance(t);
    }

    private static boolean asksAmount(String a) {
        return a.contains("多少万") || a.contains("多少资金") || a.contains("期望额度") || a.contains("大概多少");
    }

    private static boolean asksTime(String a) {
        return a.contains("什么时候") || a.contains("何时") || a.contains("打算什么时候") || a.contains("时间上");
    }

    private static boolean asksPurpose(String a) {
        return a.contains("公司用") || a.contains("个人用")
                || (a.contains("个人") && (a.contains("经营") || a.contains("周转")));
    }

    private static boolean asksBoth(String a) {
        return isAmountTimeProbe(a);
    }

    public static String humanize(String s) {
        if (!StringUtils.hasText(s)) {
            return s;
        }
        return s.replace("您需要多少资金，计划何时使用", "您大概需要多少万、什么时候用呢")
                .replace("您需要多少资金", "您大概需要多少万")
                .replace("计划何时使用", "打算什么时候用");
    }

    public static boolean isAffirmativeNeed(String t) {
        if (!StringUtils.hasText(t)) {
            return false;
        }
        String n = t.replaceAll("[\\s，,。.!！?？~～]+", "");
        return n.equals("有") || n.equals("有啊") || n.equals("有的") || n.equals("需要") || n.equals("要");
    }

    /** ASR 仅标点/空白，无实质语义 */
    public static boolean isPunctuationOnly(String t) {
        if (!StringUtils.hasText(t)) {
            return true;
        }
        return t.trim().replaceAll("[\\s，,。.!！?？~～、；;]+", "").isEmpty();
    }

    /** 接听后常见问候/语气词，不应走 RAG 向量直出 */
    public static boolean isGreetingOnly(String t) {
        if (!StringUtils.hasText(t) || isPunctuationOnly(t)) {
            return false;
        }
        String n = t.trim().replaceAll("[\\s，,。.!！?？~～、；;]+", "").toLowerCase();
        if (!StringUtils.hasText(n) || n.length() > 6) {
            return false;
        }
        return n.equals("喂") || n.equals("你好") || n.equals("您好") || n.equals("嗨")
                || n.equals("hi") || n.equals("hello") || isFillerOnly(t);
    }

    public static boolean shouldBypassRag(String t) {
        return isPunctuationOnly(t) || isGreetingOnly(t);
    }

    /**
     * 客户是在提问/追问/纠正，或 RAG 已命中 FAQ：应走 LLM+上下文，勿强行推进主线话术。
     */
    public static boolean prefersContextualLlmReply(String userText, boolean ragHasPositiveMatch) {
        if (!StringUtils.hasText(userText)) {
            return false;
        }
        String u = userText.trim();
        if (isFollowUpComplaint(u)) {
            return true;
        }
        if (isExplicitCustomerQuestion(u)) {
            return true;
        }
        if (shouldPreferMainFlowAdvance(u)) {
            return false;
        }
        return ragHasPositiveMatch && isExplicitCustomerQuestion(u);
    }

    /**
     * 短答/语气/暂缓/未知额度等：优先推进主线，不走 RAG+LLM。
     */
    public static boolean shouldPreferMainFlowAdvance(String userText) {
        if (!StringUtils.hasText(userText) || isExplicitCustomerQuestion(userText)) {
            return false;
        }
        if (isFollowUpComplaint(userText)) {
            return false;
        }
        if (DialogScriptKeywordMatcher.looksLikeRefuse(userText)
                || ForcedHangupRules.isUserFarewell(userText)) {
            return false;
        }
        if (DialogScriptKeywordMatcher.looksLikeAccept(userText)) {
            return true;
        }
        String n = userText.trim().replaceAll("[\\s，,。.!！?？~～、；;]+", "");
        if (n.length() > 10) {
            return false;
        }
        return n.contains("等一下") || n.contains("稍等") || n.contains("等会")
                || n.contains("不知道") || n.contains("不清楚") || n.contains("没想好")
                || n.equals("嗯") || n.equals("哦") || n.equals("啊");
    }

    /** 客户在问具体问题（利率/额度/身份/怎么办理等） */
    public static boolean isExplicitCustomerQuestion(String userText) {
        if (!StringUtils.hasText(userText)) {
            return false;
        }
        String u = userText.trim();
        if (ForcedHangupRules.isIdentityInquiry(u)
                || ForcedHangupRules.isCompanyOrAddressInquiry(u)
                || ForcedHangupRules.isServiceInquiry(u)
                || ForcedHangupRules.isClarificationQuestion(u)
                || ForcedHangupRules.isHearingIssue(u)) {
            return true;
        }
        return u.contains("利率") || u.contains("利息") || u.contains("额度")
                || u.contains("多少钱") || u.contains("多少万") || u.contains("怎么贷")
                || u.contains("怎么办") || u.contains("怎么办理") || u.contains("多久")
                || u.contains("在哪") || u.contains("哪里") || u.contains("谁")
                || (u.contains("多少") && (u.contains("?") || u.contains("？") || u.contains("吗") || u.contains("呢")))
                || u.contains("为什么") || u.contains("什么意思");
    }

    public static boolean isFollowUpComplaint(String userText) {
        return userText.contains("说过了") || userText.contains("我就问") || userText.contains("没回答")
                || userText.contains("答非所问") || userText.contains("你还没") || userText.contains("听不懂")
                || userText.contains("听不明白") || userText.contains("别绕") || userText.contains("直接说");
    }

    public static boolean isFillerOnly(String t) {
        if (!StringUtils.hasText(t)) {
            return true;
        }
        String n = t.replaceAll("[\\s，,。.!！?？~～、；;]+", "");
        return n.length() <= 2 && (n.equals("嗯") || n.equals("啊") || n.equals("哦") || n.equals("对")
                || n.equals("好") || n.equals("喂"));
    }

    /**
     * 大模型在客户已说清额度时仍回复「没听清」时，用槽位生成确认话术。
     */
    public static String correctUnclearReplyWhenAmountKnown(String reply, Slots slots, String userText,
                                                          List<AiChatMessage> history) {
        if (!StringUtils.hasText(reply) || slots == null || !slots.hasAmount) {
            return reply;
        }
        if (!isNumericAmountUtterance(userText) && !slots.hasAmount) {
            return reply;
        }
        if (!reply.contains("没听清") && !reply.contains("听不清") && !reply.contains("没听太清")) {
            return reply;
        }
        AskState asks = analyzeAsks(history);
        if (!slots.hasPurpose) {
            return "好的，" + slots.amountLabel + "我记下了，"
                    + pickVariant(PURPOSE_FIRST, PURPOSE_REPHRASE, asks.purposeAskCount, false);
        }
        if (!slots.hasTime) {
            return "明白，大概 " + slots.amountLabel + "，"
                    + pickVariant(TIME_FIRST, TIME_REPHRASE, asks.timeAskCount, false);
        }
        return "好的，" + slots.amountLabel + "我都记下了，还有别的想了解吗？";
    }

    private static boolean containsTimeExpr(String text) {
        return text.contains("今天") || text.contains("明天") || text.contains("后天")
                || text.contains("这个月") || text.contains("本月") || text.contains("近期")
                || text.contains("就要") || text.contains("马上") || text.contains("尽快")
                || text.contains("下周") || text.contains("下个月") || text.contains("这几天")
                || text.matches(".*\\d+\\s*月.*");
    }

    private static String parseTimeLabel(String text) {
        if (text.contains("今天")) {
            return "今天";
        }
        if (text.contains("明天")) {
            return "明天";
        }
        if (text.contains("后天")) {
            return "后天";
        }
        if (text.contains("这个月") || text.contains("本月")) {
            return "这个月";
        }
        if (text.contains("近期") || text.contains("马上") || text.contains("尽快") || text.contains("就要")) {
            return "近期";
        }
        if (text.contains("下周")) {
            return "下周";
        }
        return "近期";
    }

    private static AmountParse parseAmount(String text) {
        if (!StringUtils.hasText(text)) {
            return new AmountParse(false, "");
        }
        String t = text.replaceAll("\\s+", "");

        if (t.contains("五百万") || t.contains("500万")) {
            return new AmountParse(true, "五百万");
        }
        Matcher qwm = QIAN_WAN.matcher(t);
        if (qwm.find()) {
            return new AmountParse(true, qwm.group(1) + "千万");
        }
        if (t.contains("千万") && !t.contains("百万")) {
            return new AmountParse(true, "一千万");
        }
        Matcher bm = BAI_WAN.matcher(t);
        if (bm.find()) {
            return new AmountParse(true, bm.group(1) + "百万");
        }
        if (t.contains("百万")) {
            return new AmountParse(true, "一百万");
        }
        if (t.contains("五十") || t.contains("50万")) {
            return new AmountParse(true, "五十万");
        }
        if (t.contains("三十") || t.contains("30万")) {
            return new AmountParse(true, "三十万");
        }
        if (t.contains("二十") || t.contains("20万")) {
            return new AmountParse(true, "二十万");
        }
        Matcher longWan = WAN_LONG.matcher(t);
        if (longWan.find()) {
            return new AmountParse(true, longWan.group(1) + "万");
        }
        Matcher digitWan = Pattern.compile("(\\d{3,})万").matcher(t);
        if (digitWan.find()) {
            return new AmountParse(true, digitWan.group(1) + "万");
        }
        Matcher shortWan = WAN_SHORT.matcher(t);
        if (shortWan.find()) {
            return new AmountParse(true, shortWan.group(1) + "万");
        }
        // 「要五百万」须整段匹配，禁止「要+五」误判为五万
        Matcher yaoWan = Pattern.compile("要([一二三四五六789十两\\d百]+万)").matcher(t);
        if (yaoWan.find()) {
            return new AmountParse(true, yaoWan.group(1));
        }
        AmountParse yuan = parseYuanDigits(t);
        if (yuan.found) {
            return yuan;
        }
        String stripped = t.replaceAll("[\\s，,。.!！?？~～、]+", "");
        Matcher bareDigit = BARE_DIGIT_WAN.matcher(stripped);
        if (bareDigit.matches()) {
            return new AmountParse(true, bareDigit.group(1) + "万");
        }
        AmountParse bareCn = parseBareChineseAmount(stripped);
        if (bareCn.found) {
            return bareCn;
        }
        return new AmountParse(false, "");
    }

    /** 客户本轮在报额度（元/万），应走槽位确认，不让 RAG 误匹配 FAQ */
    public static boolean isStatingLoanAmount(String userText) {
        if (!StringUtils.hasText(userText)) {
            return false;
        }
        if (parseAmount(userText).found) {
            return true;
        }
        return isNumericAmountUtterance(userText);
    }

    private static AmountParse parseYuanDigits(String text) {
        Matcher m = Pattern.compile("(\\d{4,8})").matcher(text);
        while (m.find()) {
            try {
                long val = Long.parseLong(m.group(1));
                if (val >= 10_000L && val <= 99_999_999L) {
                    long wan = val / 10_000L;
                    if (wan >= 1) {
                        return new AmountParse(true, formatWanLabel(wan));
                    }
                }
            } catch (NumberFormatException ignored) {
                // try next match
            }
        }
        return new AmountParse(false, "");
    }

    private static String formatWanLabel(long wan) {
        return switch ((int) wan) {
            case 10 -> "十万";
            case 20 -> "二十万";
            case 30 -> "三十万";
            case 40 -> "四十万";
            case 50 -> "五十万";
            case 60 -> "六十万";
            case 70 -> "七十万";
            case 80 -> "八十万";
            case 90 -> "九十万";
            case 100 -> "一百万";
            case 200 -> "两百万";
            case 500 -> "五百万";
            default -> wan + "万";
        };
    }

    /** 口语只说「八十」「五十」等，在问额度场景下按「八十万」理解 */
    private static AmountParse parseBareChineseAmount(String stripped) {
        if (!StringUtils.hasText(stripped)) {
            return new AmountParse(false, "");
        }
        return switch (stripped) {
            case "八十", "80", "八零" -> new AmountParse(true, "八十万");
            case "五十", "50" -> new AmountParse(true, "五十万");
            case "三十", "30" -> new AmountParse(true, "三十万");
            case "二十", "20" -> new AmountParse(true, "二十万");
            case "十", "10" -> new AmountParse(true, "十万");
            case "一百", "100" -> new AmountParse(true, "一百万");
            case "六十", "60" -> new AmountParse(true, "六十万");
            case "七十", "70" -> new AmountParse(true, "七十万");
            case "九十", "90" -> new AmountParse(true, "九十万");
            default -> new AmountParse(false, "");
        };
    }

    private static boolean isAmountCorrection(String t) {
        if (!StringUtils.hasText(t)) {
            return false;
        }
        boolean complaint = t.contains("不是") || t.contains("听错") || t.contains("搞错")
                || t.contains("说过了") || t.contains("讲过") || t.contains("刚说")
                || t.contains("不是说") || t.contains("听得懂");
        return complaint && parseAmount(t).found;
    }

    private static boolean mismatchesSlotAmount(String reply, Slots slots) {
        if (!slots.hasAmount || !StringUtils.hasText(reply) || !StringUtils.hasText(slots.amountLabel)) {
            return false;
        }
        String label = slots.amountLabel;
        if (label.contains("百万") && reply.contains("五万") && !reply.contains("百万")) {
            return true;
        }
        if (label.contains("五十") && reply.contains("五万") && !reply.contains("五十")) {
            return true;
        }
        return false;
    }

    private static String normalizeProbe(String s) {
        return s.replaceAll("[\\s，,。.!！?？~～、]+", "").toLowerCase();
    }

    private static double similarityRatio(String a, String b) {
        int max = Math.max(a.length(), b.length());
        if (max == 0) {
            return 0;
        }
        int common = 0;
        int limit = Math.min(a.length(), b.length());
        for (int i = 0; i < limit; i++) {
            if (a.charAt(i) == b.charAt(i)) {
                common++;
            }
        }
        return (double) common / max;
    }

    private record AmountParse(boolean found, String label) {
    }
}
