package com.aicall.common;

import java.util.List;

/**
 * 强制结束通话规则（仅以下三类触发挂断，其它情况由对话自然进行）。
 */
public final class ForcedHangupRules {

    /** 通话最长 5 分钟 */
    public static final int MAX_CALL_SECONDS = 300;

    public static final String END_WORDS = "感谢您的时间，祝您生活愉快，再见。";

    /** 客户明确拒接/勿扰时的结束语 */
    public static final String REFUSE_END_WORDS =
            "好的，理解您的想法，那就不多打扰了，祝您生活愉快，再见。";

    /** 长时间静默无人应答时的结束语 */
    public static final String SILENCE_END_WORDS =
            "不好意思，一直没听到您回应，我先不打扰了，祝您生活愉快，再见。";

    /** TTS 限流/异常时播放（须已预合成结束语缓存） */
    public static final String TTS_FAILURE_END_WORDS =
            "抱歉，线路有点忙，稍后工作人员再联系您，祝您生活愉快，再见。";

    /** 超时结束：先说明情况再礼貌告别 */
    public static final String DURATION_END_WORDS =
            "不好意思，本次通话已到五分钟，我先不打扰您了。如有需要欢迎随时联系我们，祝您生活愉快，再见。";

    private static final List<String> ABUSE_VULGAR_KEYWORDS = List.of(
            "傻逼", "傻b", "sb", "操你", "草你", "妈的", "他妈", "尼玛", "滚蛋", "滚开",
            "神经病", "有病", "去死", "贱", "狗东西", "废物", "人渣", "妈的逼", "卧槽尼玛",
            "骂人", "辱骂", "脏话"
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
            "卡顿", "断断续续", "听不到", "听得到吗", "能听到吗", "听得见吗", "在听吗",
            "能听到", "听得到", "听得见", "能听见"
    );

    private static final List<String> CLARIFICATION_KEYWORDS = List.of(
            "什么意思", "啥意思", "没懂", "不懂", "没明白", "不明白", "新需求吗",
            "什么需求", "哪方面", "说什么", "讲什么"
    );

    /** 客户在纠正 ASR/AI 误听，或表示「刚才说错了」——不应推进主线、不应挂机 */
    private static final List<String> ASR_CORRECTION_KEYWORDS = List.of(
            "说错了", "听错了", "搞错了", "弄错了", "问错了", "答错了", "讲错了", "记错了",
            "不是这样", "不是这个", "不是那", "你搞混", "搞混了", "我说错", "理解错了", "误会了"
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

    /** 捣乱、辱骂、明显玩笑额度等：应礼貌挂机，不推进主线 */
    public static boolean isDisruptiveOrAbsurdRequest(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String n = normalizeShortUtterance(text);
        if (n.equals("骂人") || n.contains("捣乱") || n.contains("一个亿") || n.contains("亿万")
                || n.contains("千亿") || n.contains("万亿")) {
            return true;
        }
        if (n.contains("亿") && (n.contains("我要") || n.contains("给我") || n.contains("来一"))) {
            return true;
        }
        return containsAny(text, ABUSE_VULGAR_KEYWORDS);
    }

    /** 客户表示暂无资金需求，但可留备用/加微（非强硬拒接） */
    public static boolean defersFundingNeed(String userText) {
        if (userText == null || userText.isBlank()) {
            return false;
        }
        String n = normalizeShortUtterance(userText);
        return n.contains("下个月") || n.contains("以后才") || n.contains("以后再用")
                || n.contains("以后需要") || n.contains("暂时不急") || n.contains("过段时间")
                || (n.contains("以后") && n.contains("用"));
    }

    /** 客户否认社保/公积金/工资等上班族资质 */
    public static boolean deniesSocialInsuranceQualification(String userText) {
        if (userText == null || userText.isBlank()) {
            return false;
        }
        String n = normalizeShortUtterance(userText);
        if (n.contains("都没有") || n.contains("全没有") || n.contains("啥都没有")
                || n.contains("都没有啊") || n.contains("没有那些") || n.contains("没那些")
                || n.contains("那些没有") || n.contains("那些都没")) {
            return true;
        }
        if ((n.contains("没有") || n.contains("没")) && (n.contains("社保") || n.contains("公积金"))) {
            return true;
        }
        if (n.contains("没公积金") || n.contains("没社保") || n.contains("无公积金") || n.contains("无社保")) {
            return true;
        }
        if (n.contains("你讲那些") && n.contains("没有")) {
            return true;
        }
        return false;
    }

    /** 在社保/公积金盘问语境下，短答「没有/没」表示无该资质 */
    public static boolean isStandaloneNo(String normalizedUserText) {
        return normalizedUserText.equals("没有") || normalizedUserText.equals("没")
                || normalizedUserText.equals("没有啊") || normalizedUserText.equals("没啊")
                || normalizedUserText.equals("木有") || normalizedUserText.equals("没需求");
    }

    public static String complaintSoothingEndWords() {
        return "非常抱歉给您带来困扰，我这边帮您登记备注，后续不会再打扰您，祝您生活愉快，再见！";
    }

    /** 2. 明确希望不被打扰（有业务意向的短答不误判；加微信语境下「不用了」不算拒贷） */
    public static boolean wantsNoDisturbance(String text) {
        return wantsNoDisturbance(text, null);
    }

    public static boolean wantsNoDisturbance(String text, String lastAssistantText) {
        if (text == null || text.isBlank()) {
            return false;
        }
        if (declinesWeChatInvitationOnly(text, lastAssistantText)) {
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
        if (wantsNoDisturbance(userText, lastAssistantText)) {
            return true;
        }
        if (isAssetQualificationQuestion(lastAssistantText)) {
            return false;
        }
        if (isWeChatInvitationContext(lastAssistantText)) {
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

    /** 开场白在问资金备用/转贷打算（step 01） */
    public static boolean isOpeningFundingIntentQuestion(String lastAssistantText) {
        if (lastAssistantText == null || lastAssistantText.isBlank()) {
            return false;
        }
        String ai = lastAssistantText.trim();
        return (ai.contains("打算") || ai.contains("资金备用") || ai.contains("转贷"))
                && (ai.contains("资金") || ai.contains("转贷") || ai.contains("备用"));
    }

    /** 开场白后客户表示有资金/转贷打算，应进入主线 step 02 */
    public static boolean acceptsOpeningFundingIntent(String userText) {
        if (userText == null || userText.isBlank()) {
            return false;
        }
        String n = normalizeShortUtterance(userText);
        if (n.contains("没") && n.contains("打算")) {
            return false;
        }
        if (n.contains("有这个打算") || n.contains("有打算")
                || n.equals("有的") || n.equals("有啊") || n.equals("嗯有") || n.equals("是啊")
                || n.equals("有") || n.endsWith("有啊")
                || (n.contains("有") && n.contains("打算"))) {
            return true;
        }
        return false;
    }

    /** 开场后客户同意了解/介绍（非明确无打算），应进入主线而非 FAQ 加微信话术 */
    public static boolean acceptsOpeningCooperativeResponse(String userText) {
        if (userText == null || userText.isBlank()) {
            return false;
        }
        if (acceptsOpeningFundingIntent(userText)) {
            return true;
        }
        String n = normalizeShortUtterance(userText);
        if (n.contains("没") && (n.contains("打算") || n.contains("需要") || n.contains("兴趣"))) {
            return false;
        }
        return n.contains("介绍") || n.contains("了解") || n.contains("讲讲") || n.contains("说说")
                || n.contains("可以") || n.contains("行") || n.equals("好") || n.equals("嗯")
                || n.contains("有兴趣") || n.contains("想听");
    }

    /** 上一轮在邀约加微信/发资料（此处的「不用了」仅拒绝加微，不是拒贷挂机） */
    public static boolean isWeChatInvitationContext(String lastAssistantText) {
        if (lastAssistantText == null || lastAssistantText.isBlank()) {
            return false;
        }
        String ai = lastAssistantText.trim();
        return ai.contains("微信") || ai.contains("加微") || ai.contains("发产品")
                || ai.contains("产品介绍") || ai.contains("通过下") || ai.contains("发您微信")
                || ai.contains("加个微信") || ai.contains("加一下微信") || ai.contains("加下微信");
    }

    /** 仅拒绝加微信/发资料，非整通电话拒接 */
    public static boolean declinesWeChatInvitationOnly(String userText, String lastAssistantText) {
        if (!isWeChatInvitationContext(lastAssistantText) || userText == null || userText.isBlank()) {
            return false;
        }
        String n = normalizeShortUtterance(userText);
        if (n.contains("不贷款") || n.contains("不用贷") || n.contains("没需求") || n.contains("没兴趣")
                || n.contains("别打") || n.contains("骚扰")) {
            return false;
        }
        return n.equals("不用了") || n.equals("不用") || n.equals("不需要") || n.equals("不要")
                || n.equals("算了") || n.equals("算了吧") || n.equals("不行") || n.equals("不可以")
                || n.contains("不想加") || n.contains("不加微信") || n.contains("没微信")
                || n.contains("不加微") || (n.contains("不用") && n.length() <= 6)
                || (n.contains("不要") && n.length() <= 6);
    }

    /**
     * 开场白后客户明确表示没有资金/转贷打算，应播产品老师兜底并结束（非初次拒绝挽回）。
     */
    public static boolean declinesOpeningFundingIntent(String userText, String lastAssistantText) {
        if (userText == null || userText.isBlank()) {
            return false;
        }
        if (!isOpeningFundingIntentQuestion(lastAssistantText)) {
            return false;
        }
        String n = normalizeShortUtterance(userText);
        if (n.contains("没这个打算") || n.contains("没有这个打算")
                || n.contains("没打算") || n.contains("没有打算")
                || (n.contains("暂时") && (n.contains("打算") || n.contains("没有") || n.contains("没")
                || n.contains("用不") || n.contains("不需要")))
                || n.contains("用不上") || n.contains("用不到") || n.contains("用不着")
                || n.contains("现在用不上") || n.contains("现在用不到")
                || (n.contains("目前") && (n.contains("没") || n.contains("没有"))
                && (n.contains("打算") || n.contains("需要")))) {
            return true;
        }
        return declinesFundingNeed(userText, lastAssistantText);
    }

    private static String normalizeShortUtterance(String userText) {
        return userText.trim().replaceAll("[\\s，,]+", "").replaceAll("[。.!！?？~～]+", "");
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

    /** 上一轮在问主线槽位（额度/资质/职业等），短答应推进主线而非抢答 FAQ */
    public static boolean isMainFlowSlotQuestion(String lastAssistantText) {
        return isAssetQualificationQuestion(lastAssistantText) || isFundingNeedQuestion(lastAssistantText);
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

    /**
     * 挂机前统一解析礼貌结束语：优先使用业务侧已生成话术，并确保含告别语。
     */
    public static String resolvePoliteEndWords(String preferred) {
        if (preferred == null || preferred.isBlank()) {
            return END_WORDS;
        }
        String t = preferred.trim();
        if (containsFarewell(t)) {
            return t;
        }
        if (t.endsWith("。") || t.endsWith("！") || t.endsWith("!") || t.endsWith("？") || t.endsWith("?")) {
            return t + "祝您生活愉快，再见。";
        }
        return t + "，祝您生活愉快，再见。";
    }

    private static boolean containsFarewell(String text) {
        return text.contains("再见") || text.contains("拜拜") || text.contains("生活愉快");
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

    /** 明确问「你是谁/你是哪/什么公司」，区别于泛化的「在哪里」 */
    public static boolean isStrictIdentityInquiry(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String t = text.trim();
        return t.contains("你是谁") || t.contains("你是哪") || t.contains("哪位")
                || t.contains("什么公司") || t.contains("哪家公司") || t.contains("干嘛的")
                || t.contains("干什么的") || t.contains("什么单位") || t.contains("什么机构")
                || (t.contains("你") && t.contains("谁"));
    }

    /** 开场白尾部被 ASR 误识别的片段（如「您可以了解一下」） */
    public static boolean isLikelyOpeningEchoFragment(String userText, String lastAssistantText) {
        if (userText == null || lastAssistantText == null) {
            return false;
        }
        String u = userText.trim().replaceAll("[\\s，,。.!！?？~～]+", "");
        if (u.length() > 16) {
            return false;
        }
        String opening = lastAssistantText.trim();
        if (u.contains("了解一下") && opening.contains("了解")) {
            return true;
        }
        if (u.contains("咨询了解") && opening.contains("咨询")) {
            return true;
        }
        if (u.contains("提额降息") && opening.contains("提额")) {
            return true;
        }
        if (u.contains("绿色通道") && opening.contains("绿色通道")) {
            return true;
        }
        if (u.contains("建行服务商") && opening.contains("建行")) {
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

    /** 「不是拜拜」「别挂」等否定告别，勿误判挂机 */
    public static boolean isNegatedFarewell(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String t = text.trim();
        if (t.contains("不要挂") || t.contains("别挂") || t.contains("先别挂")) {
            return true;
        }
        if (t.contains("不是") || t.contains("并没有") || t.contains("没有说") || t.contains("没说")) {
            return containsAny(t, List.of("拜拜", "再见", "挂了", "先挂", "不聊", "再见"));
        }
        return false;
    }

    /** 客户说再见、要挂机（含「88」等短告别） */
    public static boolean isUserFarewell(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        if (isNegatedFarewell(text)) {
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
        int n = Math.max(1, Math.min(attempt, 5));
        return switch (n) {
            case 1 -> "您好，请问您这边可以听到吗？";
            case 2 -> "您好，请问是否可以正常沟通？";
            case 3 -> "不好意思，刚才没听清楚，麻烦您再说一遍好吗？";
            case 4 -> "喂，您还在听吗？";
            default -> "很抱歉未收到您的回应，本次通话先结束，祝您生活愉快。";
        };
    }

    /** 达到最大静默追问次数后才合规收尾挂机 */
    public static boolean isSilenceProbeHangup(int attempt, int maxAttempts) {
        return attempt >= Math.max(3, maxAttempts);
    }

    /** @deprecated 使用 {@link #isSilenceProbeHangup(int, int)} */
    @Deprecated
    public static boolean isSilenceProbeHangup(int attempt) {
        return isSilenceProbeHangup(attempt, 5);
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

    /** 超出主线/知识库覆盖范围时，转产品老师解答 */
    public static String teacherEscalationFallbackReply() {
        return "嗯，是这样的，具体业务问题稍后让专业的产品老师给您详细解答，请您保持电话通畅！";
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

    /** 客户表示 AI/ASR 听错或自己说错，应重问当前步而非推进或挂机 */
    public static boolean isAsrCorrectionOrRetraction(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String n = normalizeShortUtterance(text);
        if (n.length() > 20) {
            return false;
        }
        if (containsAny(text, ASR_CORRECTION_KEYWORDS)) {
            return true;
        }
        return n.equals("错了") || n.equals("不对啊") || n.equals("你错了");
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
        if (u.length() < 3 || a.length() < 3) {
            return false;
        }
        if (a.contains(u) || u.contains(a)) {
            return Math.min(u.length(), a.length()) >= 4;
        }
        if (u.length() >= 8 && a.length() >= 8) {
            String prefix = u.substring(0, Math.min(8, u.length()));
            if (prefix.length() >= 6 && a.contains(prefix)) {
                return true;
            }
        }
        int lcs = lcsLength(u, a);
        int max = Math.max(u.length(), a.length());
        double ratio = max > 0 ? (double) lcs / max : 0;
        if (max >= 20) {
            return ratio >= 0.55;
        }
        return ratio >= 0.68;
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
