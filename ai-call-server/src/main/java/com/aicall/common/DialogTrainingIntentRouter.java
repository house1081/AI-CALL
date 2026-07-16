package com.aicall.common;

import org.springframework.util.StringUtils;

/**
 * 训练文档标注的专项意图：在主线推进前先命中对应 FAQ，避免抢答、错答或循环。
 */
public final class DialogTrainingIntentRouter {

    public record IntentRoute(int faqFallbackNo, boolean hangup, String label) {
    }

    private DialogTrainingIntentRouter() {
    }

    /** 按优先级返回应播放的 FAQ（fallback:no），未命中返回 null */
    public static IntentRoute resolveFaqRoute(String userText, String lastAssistantText, String currentStep) {
        if (!StringUtils.hasText(userText)) {
            return null;
        }
        String u = normalize(userText);

        if (isPeerIndustryCaller(u)) {
            return new IntentRoute(41, true, "peer-hangup");
        }
        if (isPhoneNumberSourceInquiry(u)) {
            return new IntentRoute(38, false, "phone-source");
        }
        if (isMaterialsInquiry(u)) {
            return new IntentRoute(126, false, "materials");
        }
        if (isPropertyProductInquiry(u)) {
            if (u.contains("二押") || u.contains("二次抵押")) {
                return new IntentRoute(1, false, "second-pledge");
            }
            if (u.contains("单签")) {
                return new IntentRoute(34, false, "single-sign");
            }
            if (u.contains("老婆") || u.contains("配偶") || u.contains("父母") || u.contains("家人")) {
                return new IntentRoute(103, false, "family-property");
            }
            if (isRefinanceInquiry(u)) {
                return new IntentRoute(135, false, "refinance");
            }
            return new IntentRoute(147, false, "property-can-apply");
        }
        if (isManyOnlineLoanApplications(userText)) {
            return new IntentRoute(isLateQualificationStage(currentStep) ? 149 : 146, false, "online-loans");
        }
        if (isProcessingTimeInquiry(userText)) {
            return new IntentRoute(32, false, "processing-time");
        }
        if (isOnlineOfflineInquiry(u) && !ForcedHangupRules.deniesSocialInsuranceQualification(userText)) {
            return new IntentRoute(110, false, "online-offline");
        }
        if (looksLikeNoTimeOrBusy(userText)) {
            return new IntentRoute(75, false, "no-time");
        }
        if (isRefinanceInquiry(u)) {
            return new IntentRoute(135, false, "refinance");
        }
        if (isHowToApplyInquiry(u)) {
            return new IntentRoute(17, false, "how-to-apply");
        }
        if (isBankOrAgencyInquiry(u) && !shouldDeferIdentityFaqToMainFlow(userText, lastAssistantText, currentStep)) {
            return new IntentRoute(18, false, "bank-agency");
        }
        if (isOnlySocialPayrollQualification(u)) {
            return new IntentRoute(148, false, "social-payroll-only");
        }
        if (isApplicationRejectedBefore(u)) {
            return new IntentRoute(35, false, "app-rejected");
        }
        if (isAlreadyProcessedElsewhere(u)) {
            return new IntentRoute(33, false, "already-processed");
        }
        if (isOverdueOrCreditProblem(u) && !isMortgageTailPaymentConcern(u)) {
            return new IntentRoute(93, false, "credit-overdue");
        }
        if (isInterestRateQuestion(u)) {
            return new IntentRoute(8, false, "interest-rate");
        }
        return null;
    }

    /** 房产办理/产品咨询，不应被主线 step13「有房吗」抢答 */
    public static boolean isPropertyProductInquiry(String userText) {
        if (!StringUtils.hasText(userText)) {
            return false;
        }
        String u = normalize(userText);
        if (!u.contains("房") && !u.contains("按揭") && !u.contains("抵押") && !u.contains("转贷")
                && !u.contains("置换") && !u.contains("单签") && !u.contains("二押")) {
            return false;
        }
        return u.contains("可以办") || u.contains("能办") || u.contains("能不能") || u.contains("能否")
                || u.contains("怎么办") || u.contains("怎么贷") || u.contains("置换") || u.contains("转贷")
                || u.contains("二押") || u.contains("单签") || u.contains("老婆") || u.contains("配偶")
                || u.contains("父母") || u.contains("名下") && u.contains("吗");
    }

    /** 客户确认有房产（盘问语境），非产品咨询 */
    public static boolean affirmsPropertyOwnership(String userText) {
        if (!StringUtils.hasText(userText) || isPropertyProductInquiry(userText)) {
            return false;
        }
        String u = normalize(userText);
        if (u.contains("没有") || u.contains("没房")) {
            return false;
        }
        return u.equals("有") || u.equals("有啊") || u.equals("有的") || u.equals("有房")
                || u.contains("有房子") || u.contains("有房产") || u.contains("我有房");
    }

    public static boolean shouldDeferIdentityFaqToMainFlow(String userText, String lastAssistantText, String currentStep) {
        if (!StringUtils.hasText(userText)) {
            return false;
        }
        if (looksLikeSocialInsuranceLapsed(normalize(userText))) {
            return true;
        }
        if (isSocialQualificationStep(currentStep)) {
            return true;
        }
        if (ForcedHangupRules.isAssetQualificationQuestion(lastAssistantText)) {
            return true;
        }
        if (ForcedHangupRules.deniesSocialInsuranceQualification(userText)
                && (isSocialQualificationStep(currentStep)
                || ForcedHangupRules.isAssetQualificationQuestion(lastAssistantText))) {
            return true;
        }
        return false;
    }

    public static boolean blocksMainFlowAdvance(String userText, String lastAssistantText, String currentStep) {
        if (resolveFaqRoute(userText, lastAssistantText, currentStep) != null) {
            return true;
        }
        if (isPropertyProductInquiry(userText)) {
            return true;
        }
        if (shouldDeferIdentityFaqToMainFlow(userText, lastAssistantText, currentStep)) {
            return true;
        }
        if (isOverdueOrCreditProblem(normalize(userText))) {
            return true;
        }
        if (isBankOrAgencyInquiry(normalize(userText)) && ("01".equals(currentStep) || "02".equals(currentStep))) {
            return true;
        }
        if (isPhoneNumberSourceInquiry(normalize(userText))) {
            return true;
        }
        if (isMaterialsInquiry(normalize(userText))) {
            return true;
        }
        if (ForcedHangupRules.deniesSocialInsuranceQualification(userText)
                && isSocialQualificationStep(currentStep)) {
            return true;
        }
        if (looksLikeHomemakerOrFlexibleJob(userText) && "03".equals(currentStep)) {
            return true;
        }
        if (looksLikeRetired(userText)) {
            return true;
        }
        if (looksLikeNotWorkingNorBusiness(userText)) {
            return true;
        }
        if (looksLikeNoTimeOrBusy(normalize(userText)) && "01".equals(currentStep)) {
            return true;
        }
        if (ForcedHangupRules.isAsrCorrectionOrRetraction(userText)) {
            return true;
        }
        return false;
    }

    /**
     * 用户其实在回答主线盘问（短答/是或否/额度等），应推进主线，通用 FAQ 关键词不应抢答。
     * 训练文档专项 FAQ（resolveFaqRoute）与身份/地址类追问除外。
     */
    public static boolean shouldDeferKeywordFaqToMainFlow(String userText, String lastAssistantText,
                                                          String currentStep) {
        if (!StringUtils.hasText(userText) || !StringUtils.hasText(currentStep)) {
            return false;
        }
        if (ForcedHangupRules.isAsrCorrectionOrRetraction(userText)) {
            return true;
        }
        if (resolveFaqRoute(userText, lastAssistantText, currentStep) != null) {
            return false;
        }
        if (ForcedHangupRules.shouldSkipSlotOverride(userText)) {
            return false;
        }
        if (DialogSlotHelper.isExplicitCustomerQuestion(userText)
                && !looksLikeShortQualificationAnswer(userText, lastAssistantText)) {
            return false;
        }
        if (DialogSlotHelper.shouldPreferMainFlowAdvance(userText)) {
            return true;
        }
        if (ForcedHangupRules.isMainFlowSlotQuestion(lastAssistantText)
                && looksLikeShortQualificationAnswer(userText, lastAssistantText)) {
            return true;
        }
        return isQualificationMainFlowStep(currentStep)
                && looksLikeShortQualificationAnswer(userText, lastAssistantText);
    }

    private static boolean isQualificationMainFlowStep(String step) {
        return "01".equals(step) || "02".equals(step) || "03".equals(step) || "04".equals(step)
                || "05".equals(step) || "06".equals(step) || "07".equals(step) || "07A".equals(step)
                || "08".equals(step) || "09".equals(step) || "10".equals(step) || "11".equals(step)
                || "12".equals(step) || "13".equals(step) || "13A".equals(step) || "14".equals(step);
    }

    /** 像在填主线槽位的短答（非独立业务追问） */
    private static boolean looksLikeShortQualificationAnswer(String userText, String lastAssistantText) {
        if (!StringUtils.hasText(userText)) {
            return false;
        }
        String n = normalize(userText);
        if (n.length() > 16) {
            return false;
        }
        if (n.contains("怎么") || n.contains("为什么") || n.contains("能不能") || n.contains("可不可以")) {
            return false;
        }
        if (isPropertyProductInquiry(userText) || isBankOrAgencyInquiry(n) || isInterestRateQuestion(n)) {
            return false;
        }
        if (DialogSlotHelper.isExplicitCustomerQuestion(userText)) {
            return ForcedHangupRules.isMainFlowSlotQuestion(lastAssistantText);
        }
        return true;
    }

    public static boolean shouldSuppressNoNeedFaq(String userText, int faqFallbackNo) {
        if (faqFallbackNo != 70) {
            return false;
        }
        return isHowToApplyInquiry(normalize(userText))
                || isPositiveFundingIntent(normalize(userText));
    }

    public static boolean shouldSuppressMortgageTailFaq(String userText, int faqFallbackNo) {
        if (faqFallbackNo != 73) {
            return false;
        }
        return isOverdueOrCreditProblem(normalize(userText));
    }

    public static boolean shouldSuppressOnlineOfflineFaq(String userText, int faqFallbackNo) {
        if (faqFallbackNo != 110) {
            return false;
        }
        String u = normalize(userText);
        return ForcedHangupRules.deniesSocialInsuranceQualification(userText)
                || looksLikeHomemakerOrFlexibleJob(u)
                || u.contains("都没有") || u.contains("全没有");
    }

    public static boolean shouldSuppressCompanyAddressFaq(String userText, int faqFallbackNo) {
        if (faqFallbackNo != 16) {
            return false;
        }
        return isPhoneNumberSourceInquiry(normalize(userText));
    }

    public static boolean mentionsProperty(String userText) {
        if (!StringUtils.hasText(userText)) {
            return false;
        }
        if (isPropertyProductInquiry(userText)) {
            return false;
        }
        String u = normalize(userText);
        return u.contains("房") || u.contains("房产") || u.contains("按揭")
                || u.contains("全款") || u.contains("抵押");
    }

    public static boolean looksLikeHomemakerOrFlexibleJob(String userText) {
        if (!StringUtils.hasText(userText)) {
            return false;
        }
        String u = normalize(userText);
        return looksLikeRetired(userText)
                || u.contains("带小孩") || u.contains("全职") || u.contains("宝妈")
                || u.contains("家庭主妇") || u.contains("在家带") || u.contains("没上班")
                || u.contains("不上班") || u.contains("外卖") || u.contains("跑外卖")
                || u.contains("跑腿") || u.contains("滴滴") || u.contains("网约车")
                || u.contains("骑手") || u.contains("送餐") || u.contains("工地")
                || looksLikeNotWorkingNorBusiness(userText);
    }

    public static boolean looksLikeRetired(String userText) {
        if (!StringUtils.hasText(userText)) {
            return false;
        }
        return normalize(userText).contains("退休");
    }

    public static boolean looksLikeNotWorkingNorBusiness(String userText) {
        if (!StringUtils.hasText(userText)) {
            return false;
        }
        String u = normalize(userText);
        return u.contains("不上班") && u.contains("不做生意");
    }

    public static boolean looksLikeEmployeeWithSocialFund(String userText) {
        if (!StringUtils.hasText(userText)) {
            return false;
        }
        String u = normalize(userText);
        return (u.contains("上班") || u.contains("在职")) && u.contains("公积金");
    }

    public static boolean looksLikeNoTimeOrBusy(String userText) {
        if (!StringUtils.hasText(userText)) {
            return false;
        }
        String u = normalize(userText);
        return u.contains("没时间") || u.contains("没空") || u.contains("太忙")
                || u.contains("时间不够") || u.contains("顾不上");
    }

    public static boolean isProcessingTimeInquiry(String userText) {
        if (!StringUtils.hasText(userText)) {
            return false;
        }
        String u = normalize(userText);
        if (u.contains("多久") && (u.contains("办") || u.contains("下") || u.contains("下来") || u.contains("放款"))) {
            return true;
        }
        return u.contains("多长时间") || u.contains("什么时候能") || u.contains("办理时效");
    }

    public static boolean looksLikeSocialInsuranceLapsed(String userText) {
        if (!StringUtils.hasText(userText)) {
            return false;
        }
        String u = normalize(userText);
        if (!u.contains("社保") && !u.contains("公积金")) {
            return false;
        }
        return u.contains("没缴") || u.contains("没在缴") || u.contains("没交")
                || u.contains("停缴") || u.contains("断缴") || u.contains("今年没")
                || u.contains("今年没有") || u.contains("没在公司") || u.contains("自己交");
    }

    public static boolean isManyOnlineLoanApplications(String userText) {
        if (!StringUtils.hasText(userText)) {
            return false;
        }
        String u = normalize(userText);
        if (!u.contains("网贷")) {
            return false;
        }
        return u.contains("申请") || u.contains("借过") || u.contains("用过")
                || u.contains("很多") || u.contains("不少") || u.contains("频繁");
    }

    private static boolean isLateQualificationStage(String step) {
        return "12".equals(step) || "13".equals(step) || "13A".equals(step)
                || "14".equals(step) || "15".equals(step) || "17".equals(step);
    }

    private static boolean isPhoneNumberSourceInquiry(String u) {
        return u.contains("号码") && (u.contains("哪里") || u.contains("哪儿") || u.contains("怎么")
                || u.contains("哪来") || u.contains("找到") || u.contains("来的"));
    }

    private static boolean isMaterialsInquiry(String u) {
        return (u.contains("材料") || u.contains("资料")) && (u.contains("哪些") || u.contains("什么")
                || u.contains("需要") || u.contains("准备") || u.contains("带"));
    }

    private static boolean isOnlySocialPayrollQualification(String u) {
        return u.contains("社保") && (u.contains("打卡") || u.contains("工资")) && u.contains("可以贷");
    }

    private static boolean isPeerIndustryCaller(String u) {
        return u.contains("同行") || u.contains("同业") || u.contains("也是做贷款")
                || u.contains("干这行的") || u.contains("做助贷");
    }

    private static boolean isRefinanceInquiry(String u) {
        return u.contains("转贷") || u.contains("置换贷款") || u.contains("高息转")
                || u.contains("高息换") || u.contains("贷款置换") || u.contains("房贷置换")
                || (u.contains("房贷") && u.contains("置换"));
    }

    private static boolean isHowToApplyInquiry(String u) {
        if (u.contains("不需要") || u.contains("用不上") || u.contains("用不到")) {
            return false;
        }
        return (u.contains("怎么") || u.contains("如何") || u.contains("怎样"))
                && (u.contains("办理") || u.contains("申请") || u.contains("贷"));
    }

    private static boolean isBankOrAgencyInquiry(String u) {
        return u.contains("哪个银行") || u.contains("哪家银行") || u.contains("什么银行")
                || u.contains("你是哪个银行") || (u.contains("银行") && u.contains("还是"))
                || u.contains("助贷") || u.contains("还是中介")
                || (u.contains("是不是") && u.contains("银行"));
    }

    private static boolean isApplicationRejectedBefore(String u) {
        return u.contains("申请都没") || u.contains("申请没过") || u.contains("审批没过")
                || u.contains("都没过") || u.contains("批不下来") || u.contains("办不下来");
    }

    private static boolean isAlreadyProcessedElsewhere(String u) {
        return u.contains("办过了") || u.contains("已经办") || u.contains("之前办")
                || u.contains("办好了") || u.contains("贷过了");
    }

    private static boolean isOverdueOrCreditProblem(String u) {
        if (u.contains("逾期") || u.contains("黑户")) {
            return true;
        }
        if (u.contains("征信") && (u.contains("不好") || u.contains("差") || u.contains("花")
                || u.contains("有问题") || u.contains("不太"))) {
            return true;
        }
        return u.contains("信用卡") && u.contains("逾期");
    }

    private static boolean isMortgageTailPaymentConcern(String u) {
        return u.contains("尾款") || u.contains("按揭尾") || (u.contains("按揭") && u.contains("还有"));
    }

    private static boolean isInterestRateQuestion(String u) {
        return (u.contains("利息") || u.contains("利率")) && (u.contains("多少")
                || u.contains("怎么") || u.contains("几") || u.contains("吗"));
    }

    private static boolean isOnlineOfflineInquiry(String u) {
        return u.contains("线上") || u.contains("线下") || u.contains("网上办");
    }

    private static boolean isPositiveFundingIntent(String u) {
        return u.contains("需要") && !u.contains("不需要") && !u.contains("没需要")
                && (u.contains("办理") || u.contains("贷款") || u.contains("周转") || u.contains("资金"));
    }

    private static boolean isSocialQualificationStep(String step) {
        return "04".equals(step) || "05".equals(step) || "06".equals(step) || "07".equals(step);
    }

    private static String normalize(String userText) {
        return userText.trim().replaceAll("[\\s，,]+", "").replaceAll("[。.!！?？~～]+", "");
    }
}
