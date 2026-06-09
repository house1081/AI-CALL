package com.aicall.service;

import com.aicall.common.DialogScriptKeywordMatcher;
import com.aicall.common.ForcedHangupRules;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 银行贷款主线循序话术：按知识库 + 通话隔离状态 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DialogMainFlowService {

    private static final List<String> MAIN_ORDER = List.of(
            "01", "02", "03", "04", "05", "06", "07", "08", "09", "10",
            "11", "12", "13", "14", "15", "17", "18", "19", "20", "21",
            "22", "23", "24", "25", "26", "27", "28", "29", "99", "AA");

    private final DialogScriptPackRegistry scriptRegistry;

    private final Map<Integer, String> callStep = new ConcurrentHashMap<>();
    private final Map<Integer, Integer> refuseStreak = new ConcurrentHashMap<>();
    private final Map<Integer, Boolean> businessUser = new ConcurrentHashMap<>();
    private final Map<Integer, Integer> callKbId = new ConcurrentHashMap<>();

    public boolean isEnabled(int kbId) {
        return scriptRegistry.isMainFlowEnabled(kbId);
    }

    public void initCall(Integer callRecordId, int kbId) {
        if (callRecordId == null) {
            return;
        }
        callStep.put(callRecordId, "01");
        refuseStreak.put(callRecordId, 0);
        businessUser.put(callRecordId, false);
        callKbId.put(callRecordId, kbId);
    }

    /** 外呼预录开场白路径未走 firstTurn chat 时，确保主线状态已初始化 */
    public void ensureInit(Integer callRecordId, int kbId) {
        if (callRecordId == null || !isEnabled(kbId)) {
            return;
        }
        callStep.putIfAbsent(callRecordId, "01");
        refuseStreak.putIfAbsent(callRecordId, 0);
        businessUser.putIfAbsent(callRecordId, false);
        callKbId.putIfAbsent(callRecordId, kbId);
    }

    public void clearCall(Integer callRecordId) {
        if (callRecordId != null) {
            callStep.remove(callRecordId);
            refuseStreak.remove(callRecordId);
            businessUser.remove(callRecordId);
            callKbId.remove(callRecordId);
        }
    }

    private int kbOf(Integer callRecordId) {
        return callKbId.getOrDefault(callRecordId, DialogCallContextService.DEFAULT_KB_ID);
    }

    public String openingScript(int kbId) {
        return scriptRegistry.mainFlowScript("01", kbId);
    }

    public String currentStep(Integer callRecordId) {
        return callRecordId == null ? "01" : callStep.getOrDefault(callRecordId, "01");
    }

    public String nextMainLineAfterUser(Integer callRecordId, String userText) {
        if (callRecordId == null) {
            return null;
        }
        int kbId = kbOf(callRecordId);
        if (!scriptRegistry.isMainFlowEnabled(kbId)) {
            return null;
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
        if ("29".equals(step) && DialogScriptKeywordMatcher.looksLikeRefuse(userText)) {
            callStep.put(callRecordId, "21");
            return scriptRegistry.mainFlowScript("21", kbId);
        }
        if (DialogScriptKeywordMatcher.looksLikeRefuse(userText)) {
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

        String next = advanceFrom(step, callRecordId, userText);
        if (next == null) {
            return null;
        }
        callStep.put(callRecordId, next);
        return scriptRegistry.mainFlowScript(next, kbId);
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

    private String advanceFrom(String step, Integer callRecordId, String userText) {
        if ("03".equals(step)) {
            businessUser.put(callRecordId, looksLikeBusiness(userText));
            return "04";
        }
        if ("07".equals(step)) {
            return Boolean.TRUE.equals(businessUser.get(callRecordId)) ? "08" : "12";
        }
        if ("27".equals(step) || "28".equals(step)) {
            if (userText.contains("微信") || userText.contains("加微")
                    || DialogScriptKeywordMatcher.looksLikeAccept(userText)) {
                return "22";
            }
        }
        return advanceLinear(step);
    }

    private static boolean looksLikeBusiness(String userText) {
        if (!StringUtils.hasText(userText)) {
            return false;
        }
        String u = userText.trim();
        return u.contains("生意") || u.contains("经营") || u.contains("企业") || u.contains("公司")
                || u.contains("个体") || u.contains("开店") || u.contains("老板");
    }

    private static String advanceLinear(String step) {
        int idx = MAIN_ORDER.indexOf(step);
        if (idx < 0 || idx >= MAIN_ORDER.size() - 1) {
            return null;
        }
        return MAIN_ORDER.get(idx + 1);
    }
}
