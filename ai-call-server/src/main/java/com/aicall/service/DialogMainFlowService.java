package com.aicall.service;

import com.aicall.common.DialogScriptKeywordMatcher;
import com.aicall.common.DialogTrainingIntentRouter;
import com.aicall.common.ForcedHangupRules;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 银行贷款主线循序话术：按知识库 + 通话隔离状态（步骤顺序来自后台配置） */
@Slf4j
@Service
@RequiredArgsConstructor
public class DialogMainFlowService {

    private final DialogScriptPackRegistry scriptRegistry;

    private final Map<Integer, String> callStep = new ConcurrentHashMap<>();
    private final Map<Integer, Integer> refuseStreak = new ConcurrentHashMap<>();
    private final Map<Integer, Boolean> businessUser = new ConcurrentHashMap<>();
    private final Map<Integer, Boolean> homemakerUser = new ConcurrentHashMap<>();
    private final Map<Integer, Boolean> propertyPath = new ConcurrentHashMap<>();
    private final Map<Integer, Boolean> socialDenied = new ConcurrentHashMap<>();
    private final Map<Integer, Integer> callKbId = new ConcurrentHashMap<>();

    public boolean isEnabled(int kbId) {
        return scriptRegistry.isMainFlowEnabled(kbId);
    }

    public void initCall(Integer callRecordId, int kbId) {
        if (callRecordId == null) {
            return;
        }
        callStep.put(callRecordId, firstStep(kbId));
        refuseStreak.put(callRecordId, 0);
        businessUser.put(callRecordId, false);
        homemakerUser.put(callRecordId, false);
        propertyPath.put(callRecordId, false);
        socialDenied.put(callRecordId, false);
        callKbId.put(callRecordId, kbId);
    }

    public void ensureInit(Integer callRecordId, int kbId) {
        if (callRecordId == null || !isEnabled(kbId)) {
            return;
        }
        callStep.putIfAbsent(callRecordId, firstStep(kbId));
        refuseStreak.putIfAbsent(callRecordId, 0);
        businessUser.putIfAbsent(callRecordId, false);
        homemakerUser.putIfAbsent(callRecordId, false);
        propertyPath.putIfAbsent(callRecordId, false);
        socialDenied.putIfAbsent(callRecordId, false);
        callKbId.putIfAbsent(callRecordId, kbId);
    }

    public void clearCall(Integer callRecordId) {
        if (callRecordId != null) {
            callStep.remove(callRecordId);
            refuseStreak.remove(callRecordId);
            businessUser.remove(callRecordId);
            homemakerUser.remove(callRecordId);
            propertyPath.remove(callRecordId);
            socialDenied.remove(callRecordId);
            callKbId.remove(callRecordId);
        }
    }

    private int kbOf(Integer callRecordId) {
        return callKbId.getOrDefault(callRecordId, DialogCallContextService.DEFAULT_KB_ID);
    }

    private String firstStep(int kbId) {
        List<String> order = scriptRegistry.mainFlowStepOrder(kbId);
        return order.isEmpty() ? "01" : order.get(0);
    }

    public String openingScript(int kbId) {
        List<String> order = scriptRegistry.mainFlowStepOrder(kbId);
        String step = order.isEmpty() ? "01" : order.get(0);
        return scriptRegistry.mainFlowScript(step, kbId);
    }

    public String currentStep(Integer callRecordId) {
        if (callRecordId == null) {
            return "01";
        }
        return callStep.getOrDefault(callRecordId, firstStep(kbOf(callRecordId)));
    }

    public String nextMainLineAfterUser(Integer callRecordId, String userText) {
        return nextMainLineAfterUser(callRecordId, userText, null);
    }

    public String nextMainLineAfterUser(Integer callRecordId, String userText, String lastAssistantText) {
        if (callRecordId == null) {
            return null;
        }
        int kbId = kbOf(callRecordId);
        if (!scriptRegistry.isMainFlowEnabled(kbId)) {
            return null;
        }
        if (ForcedHangupRules.isAbuseVulgarOrComplaint(userText)
                || ForcedHangupRules.isDisruptiveOrAbsurdRequest(userText)) {
            return null;
        }
        if (StringUtils.hasText(lastAssistantText)
                && ForcedHangupRules.declinesWeChatInvitationOnly(userText, lastAssistantText)) {
            return continueAfterWeChatDecline(callRecordId, kbId, lastAssistantText);
        }
        String step = currentStep(callRecordId);
        if ("19".equals(step) || "20".equals(step) || "24".equals(step) || "25".equals(step)
                || "26".equals(step) || "99".equals(step)) {
            return null;
        }
        if (ForcedHangupRules.isUserFarewell(userText)) {
            callStep.put(callRecordId, "19");
            return scriptRegistry.mainFlowScript("19", kbId);
        }
        if (ForcedHangupRules.isAsrCorrectionOrRetraction(userText)) {
            return resumeAfterFallback(callRecordId);
        }
        if ("29".equals(step) && DialogScriptKeywordMatcher.looksLikeRefuse(userText, lastAssistantText)) {
            callStep.put(callRecordId, "21");
            return scriptRegistry.mainFlowScript("21", kbId);
        }
        if (DialogScriptKeywordMatcher.looksLikeRefuse(userText, lastAssistantText)) {
            int streak = refuseStreak.merge(callRecordId, 1, Integer::sum);
            if (streak == 1) {
                callStep.put(callRecordId, "27");
                return scriptRegistry.mainFlowScript("27", kbId);
            }
            if (streak == 2) {
                callStep.put(callRecordId, "28");
                return scriptRegistry.mainFlowScript("28", kbId);
            }
            callStep.put(callRecordId, "29");
            return scriptRegistry.mainFlowScript("29", kbId);
        }
        refuseStreak.put(callRecordId, 0);

        if ("18".equals(step)) {
            if (userText.contains("微信") || userText.contains("加微")) {
                callStep.put(callRecordId, "22");
                return scriptRegistry.mainFlowScript("22", kbId);
            }
            if (DialogScriptKeywordMatcher.looksLikeAccept(userText)) {
                callStep.put(callRecordId, "19");
                return scriptRegistry.mainFlowScript("19", kbId);
            }
            callStep.put(callRecordId, "20");
            return scriptRegistry.mainFlowScript("20", kbId);
        }
        if ("22".equals(step)) {
            if (userText.contains("不是") || userText.contains("否")) {
                callStep.put(callRecordId, "23");
                return scriptRegistry.mainFlowScript("23", kbId);
            }
            callStep.put(callRecordId, "24");
            return scriptRegistry.mainFlowScript("24", kbId);
        }
        if ("23".equals(step)) {
            if (userText.contains("不") && userText.contains("告诉")) {
                callStep.put(callRecordId, "26");
                return scriptRegistry.mainFlowScript("26", kbId);
            }
            callStep.put(callRecordId, "24");
            return scriptRegistry.mainFlowScript("24", kbId);
        }

        String next = advanceFrom(step, callRecordId, userText, kbId);
        if (next == null) {
            return null;
        }
        callStep.put(callRecordId, next);
        return scriptRegistry.mainFlowScript(next, kbId);
    }

    public void restoreStep(Integer callRecordId, String step) {
        if (callRecordId != null && StringUtils.hasText(step)) {
            callStep.put(callRecordId, step.trim());
        }
    }

    public String resumeAfterFallback(Integer callRecordId) {
        int kbId = kbOf(callRecordId);
        String step = currentStep(callRecordId);
        if ("19".equals(step) || "20".equals(step) || "24".equals(step) || "25".equals(step)
                || "26".equals(step) || "99".equals(step)) {
            return null;
        }
        String script = scriptRegistry.mainFlowScript(step, kbId);
        return StringUtils.hasText(script) ? script : null;
    }

    public String continueAfterWeChatDecline(Integer callRecordId, int kbId) {
        return continueAfterWeChatDecline(callRecordId, kbId, null);
    }

    /**
     * 客户仅拒绝加微信/发资料：FAQ 邀约场景走拒绝挽回，不回放当前主线步。
     */
    public String continueAfterWeChatDecline(Integer callRecordId, int kbId, String lastAssistantText) {
        if (callRecordId == null) {
            return null;
        }
        refuseStreak.put(callRecordId, 0);
        String step = currentStep(callRecordId);
        if ("22".equals(step) || "23".equals(step) || "27".equals(step) || "28".equals(step) || "29".equals(step)) {
            if (stepExists(kbId, "18")) {
                callStep.put(callRecordId, "18");
                return scriptRegistry.mainFlowScript("18", kbId);
            }
        }
        if (StringUtils.hasText(lastAssistantText)
                && ForcedHangupRules.isWeChatInvitationContext(lastAssistantText)
                && !"22".equals(step) && !"23".equals(step) && !"27".equals(step)
                && !"28".equals(step) && !"29".equals(step)) {
            if (stepExists(kbId, "27")) {
                callStep.put(callRecordId, "27");
                return scriptRegistry.mainFlowScript("27", kbId);
            }
        }
        if ("01".equals(step)) {
            businessUser.put(callRecordId, true);
            callStep.put(callRecordId, "02");
            return scriptRegistry.mainFlowScript("02", kbId);
        }
        if ("19".equals(step) || "20".equals(step) || "24".equals(step) || "25".equals(step)
                || "26".equals(step) || "99".equals(step)) {
            return null;
        }
        if (stepExists(kbId, "18")) {
            callStep.put(callRecordId, "18");
            return scriptRegistry.mainFlowScript("18", kbId);
        }
        return null;
    }

    public void markNoIntentAtOpening(Integer callRecordId) {
        if (callRecordId != null) {
            callStep.put(callRecordId, "21");
        }
    }

    private String advanceFrom(String step, Integer callRecordId, String userText, int kbId) {
        String n = userText.trim().replaceAll("[\\s，,]+", "").replaceAll("[。.!！?？~～]+", "");
        if (DialogTrainingIntentRouter.isPropertyProductInquiry(userText)) {
            return null;
        }
        if ("13".equals(step) && DialogTrainingIntentRouter.affirmsPropertyOwnership(userText)) {
            propertyPath.put(callRecordId, true);
            return stepExists(kbId, "13A") ? "13A" : nextInOrder(kbId, step);
        }
        if ("13A".equals(step)) {
            return stepExists(kbId, "14") ? "14" : nextInOrder(kbId, step);
        }
        if ("07A".equals(step)) {
            return stepExists(kbId, "12") ? "12" : nextInOrder(kbId, step);
        }
        if (Boolean.TRUE.equals(propertyPath.get(callRecordId))
                && ("04".equals(step) || "05".equals(step) || "06".equals(step) || "07".equals(step)
                || "08".equals(step) || "09".equals(step) || "10".equals(step) || "11".equals(step))) {
            return stepExists(kbId, "13") ? "13" : (stepExists(kbId, "12") ? "12" : nextInOrder(kbId, step));
        }
        if ("03".equals(step) || "04".equals(step)) {
            if (DialogTrainingIntentRouter.looksLikeHomemakerOrFlexibleJob(userText)) {
                homemakerUser.put(callRecordId, true);
                businessUser.put(callRecordId, false);
                socialDenied.put(callRecordId, true);
                return stepExists(kbId, "12") ? "12" : nextInOrder(kbId, step);
            }
        }
        if ("01".equals(step)) {
            if (ForcedHangupRules.acceptsOpeningFundingIntent(userText)) {
                businessUser.put(callRecordId, true);
            }
            return nextInOrder(kbId, step);
        }
        if ("03".equals(step)) {
            if (DialogTrainingIntentRouter.looksLikeEmployeeWithSocialFund(userText)) {
                businessUser.put(callRecordId, false);
                return stepExists(kbId, "05") ? "05" : nextInOrder(kbId, step);
            }
            if (DialogTrainingIntentRouter.looksLikeHomemakerOrFlexibleJob(userText)) {
                homemakerUser.put(callRecordId, true);
                businessUser.put(callRecordId, false);
                socialDenied.put(callRecordId, true);
                return stepExists(kbId, "12") ? "12" : nextInOrder(kbId, step);
            }
            boolean business = looksLikeBusiness(userText);
            businessUser.put(callRecordId, business);
            if (business) {
                return stepExists(kbId, "08") ? "08" : nextInOrder(kbId, step);
            }
            return nextInOrder(kbId, step);
        }
        if ("04".equals(step) || "05".equals(step) || "06".equals(step) || "07".equals(step)) {
            if (Boolean.TRUE.equals(socialDenied.get(callRecordId))
                    || Boolean.TRUE.equals(homemakerUser.get(callRecordId))) {
                return stepExists(kbId, "12") ? "12" : nextInOrder(kbId, step);
            }
            if (DialogTrainingIntentRouter.looksLikeSocialInsuranceLapsed(userText)) {
                return nextInOrder(kbId, step);
            }
            if (deniesSocialAtStep(userText, n)) {
                socialDenied.put(callRecordId, true);
                return stepExists(kbId, "12") ? "12" : nextInOrder(kbId, step);
            }
        }
        if ("08".equals(step) || "09".equals(step) || "10".equals(step) || "11".equals(step)) {
            if (deniesSocialAtStep(userText, n)) {
                businessUser.put(callRecordId, false);
                return stepExists(kbId, "12") ? "12" : nextInOrder(kbId, step);
            }
        }
        if ("07".equals(step)) {
            if (Boolean.TRUE.equals(businessUser.get(callRecordId))) {
                return stepExists(kbId, "08") ? "08" : nextInOrder(kbId, step);
            }
            return stepExists(kbId, "07A") ? "07A" : (stepExists(kbId, "12") ? "12" : nextInOrder(kbId, step));
        }
        if ("27".equals(step) || "28".equals(step)) {
            if (userText.contains("微信") || userText.contains("加微")
                    || DialogScriptKeywordMatcher.looksLikeAccept(userText)) {
                return stepExists(kbId, "22") ? "22" : nextInOrder(kbId, step);
            }
        }
        return nextInOrder(kbId, step);
    }

    private boolean stepExists(int kbId, String step) {
        return StringUtils.hasText(scriptRegistry.mainFlowScript(step, kbId));
    }

    private String nextInOrder(int kbId, String step) {
        List<String> order = scriptRegistry.mainFlowStepOrder(kbId);
        int idx = order.indexOf(step);
        if (idx < 0 || idx >= order.size() - 1) {
            return null;
        }
        return order.get(idx + 1);
    }

    private static boolean deniesSocialAtStep(String userText, String normalized) {
        if (ForcedHangupRules.deniesSocialInsuranceQualification(userText)) {
            return true;
        }
        return ForcedHangupRules.isStandaloneNo(normalized);
    }

    private static boolean looksLikeBusiness(String userText) {
        if (!StringUtils.hasText(userText)) {
            return false;
        }
        if (DialogTrainingIntentRouter.looksLikeHomemakerOrFlexibleJob(userText)) {
            return false;
        }
        String u = userText.trim();
        if (u.contains("工地") || u.contains("不上班") || u.contains("没上班")) {
            return false;
        }
        return u.contains("生意") || u.contains("经营") || u.contains("企业") || u.contains("公司")
                || u.contains("个体") || u.contains("开店") || u.contains("老板")
                || u.contains("自己干") || u.contains("自己做")
                || u.contains("档口");
    }
}

