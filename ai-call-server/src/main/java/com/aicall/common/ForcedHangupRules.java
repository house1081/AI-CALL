package com.aicall.common;

import java.util.List;

/**
 * 强制结束通话规则（仅以下三类触发挂断，其它情况由对话自然进行）。
 */
public final class ForcedHangupRules {

    /** 通话最长 5 分钟 */
    public static final int MAX_CALL_SECONDS = 300;

    public static final String END_WORDS = "感谢您的时间，祝您生活愉快，再见。";

    /** TTS 限流/异常时播放（须已预合成结束语缓存） */
    public static final String TTS_FAILURE_END_WORDS =
            "抱歉，线路有点忙，稍后工作人员再联系您，祝您生活愉快，再见。";

    /** 超时结束：先说明情况再礼貌告别 */
    public static final String DURATION_END_WORDS =
            "不好意思，本次通话已到五分钟，我先不打扰您了。如有需要欢迎随时联系我们，祝您生活愉快，再见。";

    private static final List<String> ABUSE_VULGAR_KEYWORDS = List.of(
            "傻逼", "傻b", "sb", "操你", "草你", "妈的", "他妈", "尼玛", "滚蛋", "滚开",
            "神经病", "有病", "去死", "贱", "狗东西", "废物", "人渣", "妈的逼", "卧槽尼玛"
    );

    private static final List<String> COMPLAINT_KEYWORDS = List.of(
            "投诉", "举报", "报警", "110", "12321", "工信部", "银保监", "消协",
            "起诉", "告你们", "告你", "骗子", "诈骗", "骚扰电话", "违法", "侵权"
    );

    /** 明确拒接、勿扰（不含单独「不需要」，避免与额度/用途回答混淆） */
    private static final List<String> NO_DISTURB_KEYWORDS = List.of(
            "不需要了", "我不需要", "没这个需要", "不感兴趣", "没兴趣", "不用了",
            "没有需求", "没需求", "没有这方面", "没这方面", "没这方面需求", "没有这方面需求",
            "不贷款", "不用贷", "不用贷款", "不需要贷款",
            "别打了", "不要再打", "别再打", "不要打", "别再联系", "不要联系", "别联系",
            "别烦", "不要烦", "别再烦", "不要再打扰", "别打扰", "停止拨打", "停止联系",
            "拉黑", "拒接", "骚扰", "不想听", "不想接", "别再联系我", "不要再来电"
    );

    private static final List<String> IDENTITY_INQUIRY_KEYWORDS = List.of(
            "哪里", "哪儿", "谁啊", "哪位", "什么公司", "哪家公司", "啥公司",
            "什么单位", "干嘛的", "干什么的", "什么事", "什么平台", "你是谁",
            "你是哪", "哪里的", "公司名称", "怎么称呼", "什么地点", "什么地方",
            "银行", "机构", "正规吗", "正规么", "哪一家", "什么机构", "金融公司",
            "是不是骗", "骗子吗", "诈骗吗", "小贷", "网贷", "信用社", "哪边"
    );

    private static final List<String> GREETING_ONLY_PATTERNS = List.of(
            "你好", "您好", "喂", "嗯", "啊", "哦", "hi", "hello"
    );

    private static final List<String> HEARING_ISSUE_KEYWORDS = List.of(
            "听不清", "听不见", "听不清楚", "没听清", "没听清楚", "声音小", "声音太小",
            "太小声", "大声点", "大点声", "再说一遍", "重复一遍", "信号不好", "杂音",
            "卡顿", "断断续续", "听不到"
    );

    private static final List<String> CLARIFICATION_KEYWORDS = List.of(
            "什么意思", "啥意思", "没懂", "不懂", "没明白", "不明白", "新需求吗",
            "什么需求", "哪方面", "说什么", "讲什么"
    );

    private static final List<String> BUSINESS_INTENT_KEYWORDS = List.of(
            "需求", "预算", "合作", "业务", "价格", "多少", "了解", "考虑",
            "产品", "服务", "意向", "采购", "方案", "公司", "痛点", "介绍",
            "哪些", "提供", "贷款", "周转", "资金", "用途", "经营", "个人",
            "近期", "就要", "万", "五十", "期限", "还款", "利率"
    );

    private static final List<String> SERVICE_INQUIRY_KEYWORDS = List.of(
            "哪些服务", "什么服务", "提供哪些", "有什么产品", "能办什么", "业务范围",
            "怎么合作", "办理什么", "做什么业务"
    );

    /** 客户明确告别、要挂机（非单独「嗯」等短答） */
    private static final List<String> FAREWELL_KEYWORDS = List.of(
            "拜拜", "再见", "挂了", "先挂", "不聊了", "不说了", "先这样", "先不打扰",
            "就这样吧", "好了就这样", "谢谢不用", "不用谢谢", "可以挂了"
    );

    /** 1. 辱骂、脏话、投诉类 */
    public static boolean isAbuseVulgarOrComplaint(String text) {
        return containsAny(text, ABUSE_VULGAR_KEYWORDS) || containsAny(text, COMPLAINT_KEYWORDS);
    }

    /** 2. 明确希望不被打扰（有业务意向的短答不误判） */
    public static boolean wantsNoDisturbance(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        if (containsAny(text, NO_DISTURB_KEYWORDS)) {
            return true;
        }
        if (hasBusinessIntent(text)) {
            return false;
        }
        String t = text.trim();
        return t.equals("不需要") || t.equals("不用") || t.startsWith("不需要，") || t.startsWith("不需要。");
    }

    /**
     * 客户拒绝贷款/资金需求（须结合上一轮 AI 语境，避免「有车吗→没有」误判）。
     */
    public static boolean declinesFundingNeed(String userText, String lastAssistantText) {
        if (userText == null || userText.isBlank()) {
            return false;
        }
        if (wantsNoDisturbance(userText)) {
            return true;
        }
        if (isAssetQualificationQuestion(lastAssistantText)) {
            return false;
        }
        String n = userText.trim().replaceAll("[\\s，,]+", "").replaceAll("[。.!！?？~～]+", "");
        if (n.contains("没有需求") || n.contains("没需求") || n.contains("没这方面") || n.contains("没有这方面")
                || n.contains("不贷款") || n.contains("不用贷")) {
            return true;
        }
        if (isStandaloneNo(n) && isFundingNeedQuestion(lastAssistantText)) {
            return true;
        }
        return false;
    }

    /** 上一轮在问资产/资质（此处的「没有」是客观回答，不是拒贷） */
    public static boolean isAssetQualificationQuestion(String lastAssistantText) {
        if (lastAssistantText == null || lastAssistantText.isBlank()) {
            return false;
        }
        String ai = lastAssistantText.trim();
        return ai.contains("有车") || ai.contains("有房") || ai.contains("房子")
                || ai.contains("公积金") || ai.contains("社保") || ai.contains("保险")
                || ai.contains("信用卡") || ai.contains("芝麻") || ai.contains("微粒贷")
                || ai.contains("上班") || ai.contains("做生意") || ai.contains("工资")
                || ai.contains("流水") || ai.contains("营业执照");
    }

    private static boolean isFundingNeedQuestion(String lastAssistantText) {
        if (lastAssistantText == null || lastAssistantText.isBlank()) {
            return false;
        }
        String ai = lastAssistantText.trim();
        return ai.contains("资金") || ai.contains("周转") || ai.contains("贷款")
                || ai.contains("用款") || ai.contains("融资") || ai.contains("需求")
                || ai.contains("多少万") || ai.contains("多少资金") || ai.contains("期望额度");
    }

    private static boolean isStandaloneNo(String normalizedUserText) {
        return normalizedUserText.equals("没有") || normalizedUserText.equals("没")
                || normalizedUserText.equals("没有啊") || normalizedUserText.equals("没啊")
                || normalizedUserText.equals("木有") || normalizedUserText.equals("没需求");
    }

    /** 3. 通话超过 5 分钟 */
    public static boolean isCallDurationExceeded(int elapsedSeconds) {
        return elapsedSeconds >= MAX_CALL_SECONDS;
    }

    /** @deprecated 仅兼容旧调用，等价于 {@link #wantsNoDisturbance} */
    public static boolean containsRefuseKeyword(String text) {
        return wantsNoDisturbance(text);
    }

    public static String endWordsFor(String hangupType) {
        if (HangupType.FORCE_DURATION.equals(hangupType)) {
            return DURATION_END_WORDS;
        }
        return END_WORDS;
    }

    public static boolean hasBusinessIntent(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String t = text.trim();
        for (String kw : BUSINESS_INTENT_KEYWORDS) {
            if (t.contains(kw)) {
                return true;
            }
        }
        return false;
    }

    /** 公司/办公地址类问询，应走知识库 FAQ，不走身份快答或槽位追问 */
    public static boolean isCompanyOrAddressInquiry(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String t = text.trim();
        if (t.contains("公司地址") || t.contains("办公地址") || t.contains("门店地址") || t.contains("门店在哪")) {
            return true;
        }
        boolean asksLocation = t.contains("在哪") || t.contains("哪里") || t.contains("哪儿")
                || t.contains("地址") || t.contains("位置") || t.contains("什么地方");
        if (!asksLocation) {
            return false;
        }
        return t.contains("公司") || t.contains("单位") || t.contains("你们")
                || t.contains("办公") || t.contains("门店");
    }

    /** 用户明确在问 FAQ 时，槽位逻辑不应把回复改成额度/时间追问 */
    public static boolean shouldSkipSlotOverride(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        return isCompanyOrAddressInquiry(text) || isServiceInquiry(text) || isIdentityInquiry(text);
    }

    public static boolean isIdentityInquiry(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String t = text.trim();
        if (isCompanyOrAddressInquiry(t)) {
            return false;
        }
        for (String kw : IDENTITY_INQUIRY_KEYWORDS) {
            if (t.contains(kw)) {
                return true;
            }
        }
        if (t.contains("还是") && (t.contains("银行") || t.contains("公司")
                || t.contains("机构") || t.contains("平台"))) {
            return true;
        }
        return false;
    }

    public static boolean isShortGreetingOnly(String text) {
        if (text == null) {
            return false;
        }
        String t = text.trim().replaceAll("[\\s，,。.!！?？~～]+", "");
        if (t.length() > 8) {
            return false;
        }
        for (String g : GREETING_ONLY_PATTERNS) {
            if (t.equalsIgnoreCase(g) || t.contains(g)) {
                return true;
            }
        }
        return false;
    }

    public static boolean isServiceInquiry(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String t = text.trim();
        if (containsAny(t, SERVICE_INQUIRY_KEYWORDS)) {
            return true;
        }
        return t.contains("服务") && (t.contains("哪些") || t.contains("什么") || t.contains("提供"));
    }

    public static String identityFallbackReply() {
        return "我是建行服务商，帮客户走绿色通道提额降息，您有需求吗？";
    }

    public static String serviceFallbackReply() {
        return "我们主要做信用贷和周转贷，您大概想贷多少、用在哪方面？";
    }

    /** 客户说再见、要挂机（含「88」等短告别） */
    public static boolean isUserFarewell(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String t = text.trim().replaceAll("[\\s，,。.!！?？~～]+", "");
        if (t.matches("88+") || t.equals("886")) {
            return true;
        }
        if (t.equals("拜拜") || t.equals("再见") || t.equals("挂了") || t.equals("先挂")) {
            return true;
        }
        if (t.length() <= 2) {
            return false;
        }
        return containsAny(text, FAREWELL_KEYWORDS);
    }

    /** 分段模式：客户长时间无回应时的标准追问话术（量产固化） */
    public static String turnBasedSilenceProbe(int attempt) {
        int n = Math.max(1, Math.min(attempt, 3));
        return switch (n) {
            case 1 -> "您好，请问您这边可以听到吗？";
            case 2 -> "您好，请问是否可以正常沟通？";
            default -> "很抱歉未收到您的回应，本次通话先结束，祝您生活愉快。";
        };
    }

    /** 第 3 次静默追问即为合规收尾语 */
    public static boolean isSilenceProbeHangup(int attempt) {
        return attempt >= 3;
    }

    /** 检测到语音但 ASR 未识别 / ASR 超时时的本地提示 */
    public static String turnBasedUnclearAsrNudge() {
        return "您好，我没听清，请您再说一遍。";
    }

    /** LLM 接口超时或异常时的统一兜底话术 */
    public static String llmTimeoutFallbackReply() {
        return "非常抱歉，系统暂时无法为您解答，请稍后再试";
    }

    /** 知识库无匹配时的固定兜底（禁止 AI 自由编造） */
    public static String knowledgeNoMatchFallbackReply() {
        return "这个问题我暂时无法准确回答，我帮您记录一下，稍后会有同事跟您联系。";
    }

    /** 固定产品介绍套话（易与 LLM/兜底重复播放） */
    public static boolean isGenericProductIntro(String reply) {
        if (reply == null || reply.isBlank()) {
            return false;
        }
        String norm = normalizeForEcho(reply);
        String intro = normalizeForEcho(serviceFallbackReply());
        if (norm.equals(intro)) {
            return true;
        }
        return reply.contains("主要做个人和小微企业") && reply.contains("信用贷")
                && reply.contains("周转贷");
    }

    public static boolean isCooperativeAnswer(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        if (hasBusinessIntent(text)) {
            return true;
        }
        String t = text.trim().replaceAll("[\\s，,。.!！?？~～]+", "");
        if (t.length() > 20) {
            return false;
        }
        return t.contains("个人用途") || t.contains("经营") || t.contains("个人")
                || t.contains("用途") || t.contains("近期") || t.contains("就要")
                || t.contains("五十") || t.contains("万") || t.matches(".*\\d+.*");
    }

    public static String continueDialogReply(String userText) {
        if (userText == null) {
            return "好的，记下了，您期望分多少期还款呢？";
        }
        String t = userText.trim();
        if (t.contains("个人")) {
            return "嗯，个人用的话有消费贷、信用贷可以选，您大概能接受什么利率、分几期呢？";
        }
        if (t.contains("经营")) {
            return "经营周转我们也有小微贷、流水贷，您营业执照满一年了吗？";
        }
        if (t.contains("近期") || t.contains("就要")) {
            return "近期要用的话我们尽量帮您赶进度，您大概需要多少万呢？";
        }
        if (t.contains("五十") || t.contains("万")) {
            return "好的，这个额度我们要看下征信和收入，您主要是个人用还是公司用呢？";
        }
        if (isAffirmativeShort(t)) {
            return "嗯好的，那您这边大概想用多少万呢？";
        }
        return "好的，我再帮您补一项，您说下期望额度或者还款方式就行。";
    }

    private static boolean isAffirmativeShort(String t) {
        String n = t.replaceAll("[\\s，,。.!！?？~～]+", "");
        return n.equals("有") || n.equals("有啊") || n.equals("有的") || n.equals("需要") || n.equals("要")
                || n.equals("哦有") || n.equals("嗯有");
    }

    public static boolean isHearingIssue(String text) {
        return containsAny(text, HEARING_ISSUE_KEYWORDS);
    }

    public static boolean isClarificationQuestion(String text) {
        return containsAny(text, CLARIFICATION_KEYWORDS);
    }

    public static String hearingFallbackReply() {
        return "不好意思，这边信号可能有点不好，您那边声音有点小，麻烦您再说一遍好吗？";
    }

    public static String clarificationFallbackReply() {
        return "不好意思我没听清楚，麻烦您再说一遍好吗？";
    }

    /** 转写是否过短、含糊或像未说完的片段 */
    public static boolean isVagueOrUnclearTranscript(String text) {
        if (text == null || text.isBlank()) {
            return true;
        }
        String t = text.trim();
        if (isSubstantiveShortAnswer(t)) {
            return false;
        }
        if (hasBusinessIntent(t) || isIdentityInquiry(t) || isHearingIssue(t)) {
            return false;
        }
        String stripped = t.replaceAll("[\\s，,。.!！?？~～、呃啊嗯哦唉哈]+", "");
        if (stripped.length() <= 1) {
            return true;
        }
        if (stripped.length() <= 3 && !containsAny(t, GREETING_ONLY_PATTERNS)) {
            return true;
        }
        if (t.endsWith("，") || t.endsWith("、") || t.endsWith("的") || t.endsWith("是")
                || t.endsWith("或") || t.endsWith("还")) {
            return true;
        }
        return stripped.length() <= 4 && containsAny(t, List.of("那个", "这个", "什么", "怎么"));
    }

    private static boolean isSubstantiveShortAnswer(String t) {
        if (t.length() > 10) {
            return false;
        }
        return containsAny(t, List.of(
                "有", "没有", "没", "不需要", "不用", "要", "好", "行", "可以", "不行", "否",
                "万", "千", "百", "十万", "百万", "需要", "不要", "在", "不在"));
    }

    public static boolean isLikelyAsrEcho(String userText, String lastAssistantText) {
        if (userText == null || lastAssistantText == null) {
            return false;
        }
        String u = normalizeForEcho(userText);
        String a = normalizeForEcho(lastAssistantText);
        if (u.length() < 4 || a.length() < 4) {
            return false;
        }
        if (a.contains(u) || u.contains(a)) {
            return Math.min(u.length(), a.length()) >= 6;
        }
        int lcs = lcsLength(u, a);
        int max = Math.max(u.length(), a.length());
        return max > 0 && (double) lcs / max >= 0.68;
    }

    private static int lcsLength(String a, String b) {
        int m = a.length();
        int n = b.length();
        int[] prev = new int[n + 1];
        for (int i = 1; i <= m; i++) {
            int[] curr = new int[n + 1];
            for (int j = 1; j <= n; j++) {
                if (a.charAt(i - 1) == b.charAt(j - 1)) {
                    curr[j] = prev[j - 1] + 1;
                } else {
                    curr[j] = Math.max(prev[j], curr[j - 1]);
                }
            }
            prev = curr;
        }
        return prev[n];
    }

    private static String normalizeForEcho(String s) {
        return s.trim()
                .replaceAll("[\\s，,。.!！?？~～、；;：:\"\"''「」【】\\(\\)（）\\[\\]]+", "")
                .replace("你好", "您好")
                .replace("想问一下", "想问")
                .replace("想问下", "想问")
                .replace("问一下", "问")
                .toLowerCase();
    }

    private static boolean containsAny(String text, List<String> keywords) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String t = text.trim();
        for (String kw : keywords) {
            if (t.contains(kw)) {
                return true;
            }
        }
        return false;
    }

    public static boolean aiReplyIsBusinessProbe(String reply) {
        if (reply == null || reply.isBlank()) {
            return false;
        }
        String t = reply;
        return t.contains("需求") || t.contains("预算") || t.contains("痛点")
                || t.contains("合作意向") || t.contains("业务需求");
    }

    private ForcedHangupRules() {}
}
