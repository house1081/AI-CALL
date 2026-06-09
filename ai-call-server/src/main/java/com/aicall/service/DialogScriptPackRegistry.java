package com.aicall.service;

import com.aicall.common.DialogScriptKeywordMatcher;
import com.aicall.common.DialogTrainingDataType;
import com.aicall.common.ForcedHangupRules;
import com.aicall.config.DialogRagProperties;
import com.aicall.entity.DialogTrainingQa;
import com.aicall.mapper.DialogTrainingQaMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 话术包内存索引（按知识库 kbId 隔离：主线 + 关键词兜底） */
@Slf4j
@Service
@RequiredArgsConstructor
public class DialogScriptPackRegistry {

    private record PackState(List<DialogScriptKeywordMatcher.KeywordRule> keywordRules,
                             Map<String, String> mainFlowScripts) {
    }

    private final DialogRagProperties dialogRagProperties;
    private final DialogTrainingQaMapper dialogTrainingQaMapper;

    private final ConcurrentHashMap<Integer, PackState> byKb = new ConcurrentHashMap<>();

    public boolean isMainFlowEnabled(Integer kbId) {
        return dialogRagProperties.isEnabled()
                && dialogRagProperties.isLoanMainFlowEnabled()
                && mainFlowSize(kbId) > 0;
    }

    public void reloadFromDb() {
        byKb.clear();
        List<DialogTrainingQa> rows = dialogTrainingQaMapper.selectList(
                new LambdaQueryWrapper<DialogTrainingQa>().eq(DialogTrainingQa::getStatus, 1));
        Map<Integer, List<DialogScriptKeywordMatcher.KeywordRule>> rulesMap = new LinkedHashMap<>();
        Map<Integer, Map<String, String>> flowMap = new LinkedHashMap<>();
        for (DialogTrainingQa row : rows) {
            if (row == null || !StringUtils.hasText(row.getStandardAnswer())) {
                continue;
            }
            int kbId = row.getKbId() != null ? row.getKbId() : DialogCallContextService.DEFAULT_KB_ID;
            String remark = row.getRemark() != null ? row.getRemark().trim() : "";
            if (remark.startsWith("flow:")) {
                flowMap.computeIfAbsent(kbId, k -> new LinkedHashMap<>())
                        .put(remark.substring(5), row.getStandardAnswer().trim());
                continue;
            }
            if (remark.startsWith("fallback:") || !row.getQuestion().trim().startsWith("[主线")) {
                List<String> kws = DialogScriptKeywordMatcher.parseKeywords(row.getQuestion());
                if (!kws.isEmpty()) {
                    int type = row.getDataType() != null ? row.getDataType() : DialogTrainingDataType.MANUAL_CORRECTION;
                    rulesMap.computeIfAbsent(kbId, k -> new ArrayList<>())
                            .add(new DialogScriptKeywordMatcher.KeywordRule(
                                    row.getId(), kws, row.getStandardAnswer().trim(), type));
                }
            }
        }
        for (Map.Entry<Integer, Map<String, String>> e : flowMap.entrySet()) {
            List<DialogScriptKeywordMatcher.KeywordRule> rules =
                    List.copyOf(rulesMap.getOrDefault(e.getKey(), List.of()));
            byKb.put(e.getKey(), new PackState(rules, Map.copyOf(e.getValue())));
        }
        for (Map.Entry<Integer, List<DialogScriptKeywordMatcher.KeywordRule>> e : rulesMap.entrySet()) {
            byKb.computeIfAbsent(e.getKey(), k -> new PackState(List.of(), Map.of()));
            PackState old = byKb.get(e.getKey());
            byKb.put(e.getKey(), new PackState(List.copyOf(e.getValue()), old.mainFlowScripts()));
        }
        log.info("[话术包] 已加载 知识库数={}", byKb.size());
        byKb.forEach((kb, pack) -> log.info("[话术包] kb={} 主线={} 兜底={}",
                kb, pack.mainFlowScripts().size(), pack.keywordRules().size()));
    }

    private PackState pack(int kbId) {
        return byKb.getOrDefault(kbId, new PackState(List.of(), Map.of()));
    }

    public DialogScriptKeywordMatcher.KeywordRule matchKeyword(String userText, int kbId) {
        return DialogScriptKeywordMatcher.match(userText, pack(kbId).keywordRules());
    }

    public String mainFlowScript(String step, int kbId) {
        if (step == null) {
            return "";
        }
        return pack(kbId).mainFlowScripts().getOrDefault(step, "");
    }

    public int mainFlowSize(int kbId) {
        return pack(kbId).mainFlowScripts().size();
    }

    public int fallbackRuleSize(int kbId) {
        return pack(kbId).keywordRules().size();
    }

    public String silenceProbeText(int attempt, int kbId) {
        int n = Math.max(1, Math.min(attempt, 3));
        for (DialogScriptKeywordMatcher.KeywordRule rule : pack(kbId).keywordRules()) {
            for (String kw : rule.keywords()) {
                if (kw.contains("静默") || kw.contains("没声音")) {
                    String[] parts = rule.answer().split("[；;]");
                    if (parts.length >= n) {
                        return parts[n - 1].trim();
                    }
                    return rule.answer().trim();
                }
            }
        }
        return ForcedHangupRules.turnBasedSilenceProbe(n);
    }
}
