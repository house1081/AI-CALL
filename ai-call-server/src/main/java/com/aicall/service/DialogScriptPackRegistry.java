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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 话术包内存索引（按知识库 kbId 隔离：主线 + 关键词兜底） */
@Slf4j
@Service
@RequiredArgsConstructor
public class DialogScriptPackRegistry {

    private record FlowEntry(String step, int order, String script) {
    }

    private record PackState(List<DialogScriptKeywordMatcher.KeywordRule> keywordRules,
                             Map<String, String> mainFlowScripts,
                             List<String> mainFlowStepOrder) {
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
        List<DialogTrainingQa> rows = dialogTrainingQaMapper.selectList(
                new LambdaQueryWrapper<DialogTrainingQa>().eq(DialogTrainingQa::getStatus, 1));
        Map<Integer, List<DialogScriptKeywordMatcher.KeywordRule>> rulesMap = new LinkedHashMap<>();
        Map<Integer, List<FlowEntry>> flowEntries = new LinkedHashMap<>();
        for (DialogTrainingQa row : rows) {
            if (row == null || !StringUtils.hasText(row.getStandardAnswer())) {
                continue;
            }
            int kbId = row.getKbId() != null ? row.getKbId() : DialogCallContextService.DEFAULT_KB_ID;
            String remark = row.getRemark() != null ? row.getRemark().trim() : "";
            if (remark.startsWith("flow:")) {
                String step = remark.substring(5).trim();
                int order = row.getFlowOrder() != null ? row.getFlowOrder() : 9999;
                flowEntries.computeIfAbsent(kbId, k -> new ArrayList<>())
                        .add(new FlowEntry(step, order, row.getStandardAnswer().trim()));
                continue;
            }
            String question = row.getQuestion();
            if (!StringUtils.hasText(question)) {
                continue;
            }
            String q = question.trim();
            if (remark.startsWith("fallback:") || !q.startsWith("[主线")) {
                List<String> kws = DialogScriptKeywordMatcher.parseKeywords(q);
                if (!kws.isEmpty()) {
                    int type = row.getDataType() != null ? row.getDataType() : DialogTrainingDataType.MANUAL_CORRECTION;
                    rulesMap.computeIfAbsent(kbId, k -> new ArrayList<>())
                            .add(new DialogScriptKeywordMatcher.KeywordRule(
                                    row.getId(), kws, row.getStandardAnswer().trim(), type));
                }
            }
        }
        Map<Integer, PackState> next = new LinkedHashMap<>();
        for (Map.Entry<Integer, List<FlowEntry>> e : flowEntries.entrySet()) {
            List<FlowEntry> sorted = new ArrayList<>(e.getValue());
            sorted.sort(Comparator.comparingInt(FlowEntry::order).thenComparing(FlowEntry::step));
            Map<String, String> scripts = new LinkedHashMap<>();
            List<String> stepOrder = new ArrayList<>();
            for (FlowEntry fe : sorted) {
                scripts.put(fe.step(), fe.script());
                stepOrder.add(fe.step());
            }
            List<DialogScriptKeywordMatcher.KeywordRule> rules =
                    List.copyOf(rulesMap.getOrDefault(e.getKey(), List.of()));
            next.put(e.getKey(), new PackState(rules, Map.copyOf(scripts), List.copyOf(stepOrder)));
        }
        for (Map.Entry<Integer, List<DialogScriptKeywordMatcher.KeywordRule>> e : rulesMap.entrySet()) {
            next.computeIfAbsent(e.getKey(), k -> new PackState(List.of(), Map.of(), List.of()));
            PackState old = next.get(e.getKey());
            next.put(e.getKey(), new PackState(List.copyOf(e.getValue()), old.mainFlowScripts(), old.mainFlowStepOrder()));
        }
        for (Integer key : List.copyOf(byKb.keySet())) {
            if (!next.containsKey(key)) {
                byKb.remove(key);
            }
        }
        byKb.putAll(next);
        log.info("[话术包] 已加载 知识库数={}", byKb.size());
        byKb.forEach((kb, pack) -> log.info("[话术包] kb={} 主线={} 兜底={}",
                kb, pack.mainFlowScripts().size(), pack.keywordRules().size()));
    }

    private PackState pack(int kbId) {
        return byKb.getOrDefault(kbId, new PackState(List.of(), Map.of(), List.of()));
    }

    public List<String> mainFlowStepOrder(int kbId) {
        return pack(kbId).mainFlowStepOrder();
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

    public List<String> distinctMainFlowScripts(int kbId) {
        return pack(kbId).mainFlowScripts().values().stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .toList();
    }

    public int fallbackRuleSize(int kbId) {
        return pack(kbId).keywordRules().size();
    }

    public String silenceProbeText(int attempt, int kbId) {
        int n = Math.max(1, Math.min(attempt, 5));
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
