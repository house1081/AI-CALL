package com.aicall.service;

import com.aicall.common.DialogSlotHelper;
import com.aicall.dto.AiCallSummaryRequest;
import com.aicall.dto.AiCallSummaryResponse;
import com.aicall.service.CallSessionService.EndReq;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 通话结束时提取意向五字段，并结合规则校正等级（避免客户已表示需要资金仍判 D）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CallEndSummaryService {

    private final OllamaChatService ollamaChatService;
    private final IntentLevelService intentLevelService;

    public void fillEndReqFromDialog(EndReq end, String dialogText, int callDurationSec) {
        if (end == null) {
            return;
        }
        String ruleLevel = intentLevelService.inferFromDialog(dialogText, callDurationSec);
        if (!StringUtils.hasText(dialogText)) {
            end.setLevel(ruleLevel);
            return;
        }
        AiCallSummaryResponse summary = null;
        try {
            AiCallSummaryRequest req = new AiCallSummaryRequest();
            req.setDialogText(dialogText);
            summary = ollamaChatService.summarize(req);
        } catch (Exception e) {
            log.warn("[意向提取] LLM 失败，使用规则等级: {}", e.getMessage());
        }
        if (summary != null) {
            if (StringUtils.hasText(summary.getCustomerNeed())) {
                end.setCustomerNeed(summary.getCustomerNeed());
            }
            if (StringUtils.hasText(summary.getCustomerPain())) {
                end.setCustomerPain(summary.getCustomerPain());
            }
            if (StringUtils.hasText(summary.getBudget())) {
                end.setBudget(summary.getBudget());
            }
            if (StringUtils.hasText(summary.getNextTime())) {
                end.setNextTime(summary.getNextTime());
            }
            String merged = intentLevelService.mergeLevel(summary.getLevel(), ruleLevel);
            end.setLevel(merged);
            log.info("[意向提取] LLM={} 规则={} 最终={} need={}",
                    summary.getLevel(), ruleLevel, merged, summary.getCustomerNeed());
        } else {
            end.setLevel(ruleLevel);
            applyRuleFields(end, dialogText, ruleLevel);
            log.info("[意向提取] 仅规则判定 level={}", ruleLevel);
        }
    }

    private void applyRuleFields(EndReq end, String dialogText, String level) {
        var history = IntentLevelService.parseDialog(dialogText);
        var slots = DialogSlotHelper.extract(history, "");
        if (slots.needConfirmed && StringUtils.hasText(slots.amountLabel)) {
            end.setCustomerNeed("贷款/周转需求，额度约" + slots.amountLabel);
            end.setBudget(slots.amountLabel);
        } else if (slots.needConfirmed) {
            end.setCustomerNeed("有资金/贷款需求");
        }
        if (slots.hasTime && StringUtils.hasText(slots.timeLabel)) {
            end.setNextTime(slots.timeLabel);
        }
        if ("A".equals(level) || "B".equals(level)) {
            if (!StringUtils.hasText(end.getCustomerNeed())) {
                end.setCustomerNeed("有了解或办理意向");
            }
        }
    }
}
