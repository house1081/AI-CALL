package com.aicall.service;

import com.aicall.common.BizException;
import com.aicall.common.DialogTrainingDataType;
import com.aicall.dto.MainFlowStepDto;
import com.aicall.dto.MainFlowStepSaveRequest;
import com.aicall.entity.DialogTrainingQa;
import com.aicall.mapper.DialogTrainingQaMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 管理端：主线流程节点配置（AI 实时按文案播报；预录按节点上传录音） */
@Slf4j
@Service
@RequiredArgsConstructor
public class DialogMainFlowAdminService {

    private static final Pattern MAIN_QUESTION = Pattern.compile("^\\[主线([^\\]]+)](.*)$");

    /** 默认银行贷款主线顺序（用于迁移 flow_order） */
    private static final List<String> DEFAULT_STEP_ORDER = List.of(
            "01", "02", "03", "04", "05", "06", "07", "08", "09", "10",
            "11", "12", "13", "14", "15", "17", "18", "19", "20", "21",
            "22", "23", "24", "25", "26", "27", "28", "29", "99", "AA");

    private final DialogTrainingQaMapper dialogTrainingQaMapper;
    private final DialogScriptPackRegistry dialogScriptPackRegistry;

    @PostConstruct
    void backfillFlowOrderOnStartup() {
        try {
            List<DialogTrainingQa> flows = dialogTrainingQaMapper.selectList(
                    new LambdaQueryWrapper<DialogTrainingQa>().likeRight(DialogTrainingQa::getRemark, "flow:"));
            boolean changed = false;
            for (DialogTrainingQa row : flows) {
                if (row.getFlowOrder() != null) {
                    continue;
                }
                String step = stepFromRemark(row.getRemark());
                int idx = DEFAULT_STEP_ORDER.indexOf(step);
                row.setFlowOrder(idx >= 0 ? (idx + 1) * 10 : 9999);
                dialogTrainingQaMapper.updateById(row);
                changed = true;
            }
            if (changed) {
                dialogScriptPackRegistry.reloadFromDb();
                log.info("[主线流程] 已补全 flow_order 字段");
            }
        } catch (Exception e) {
            log.debug("[主线流程] flow_order 补全跳过: {}", e.getMessage());
        }
    }

    public List<MainFlowStepDto> listSteps(int kbId) {
        List<DialogTrainingQa> rows = dialogTrainingQaMapper.selectList(
                new LambdaQueryWrapper<DialogTrainingQa>()
                        .eq(DialogTrainingQa::getKbId, kbId)
                        .likeRight(DialogTrainingQa::getRemark, "flow:")
                        .eq(DialogTrainingQa::getStatus, 1));
        rows.sort(Comparator
                .comparing((DialogTrainingQa r) -> r.getFlowOrder() != null ? r.getFlowOrder() : 9999)
                .thenComparing(r -> stepFromRemark(r.getRemark())));
        List<MainFlowStepDto> out = new ArrayList<>();
        for (DialogTrainingQa row : rows) {
            out.add(toDto(row));
        }
        return out;
    }

    public MainFlowStepDto saveStep(MainFlowStepSaveRequest req) {
        if (req == null || !StringUtils.hasText(req.getStepCode()) || !StringUtils.hasText(req.getScript())) {
            throw new BizException("节点编号与播报文案不能为空");
        }
        int kbId = req.getKbId() != null ? req.getKbId() : DialogCallContextService.DEFAULT_KB_ID;
        String stepCode = req.getStepCode().trim().toUpperCase();
        String scene = StringUtils.hasText(req.getSceneName()) ? req.getSceneName().trim() : "节点" + stepCode;
        if (req.getScript().trim().length() > 2000) {
            throw new BizException("播报文案不超过2000字");
        }

        DialogTrainingQa row;
        if (req.getId() != null) {
            row = dialogTrainingQaMapper.selectById(req.getId());
            if (row == null || !isFlowRow(row)) {
                throw new BizException("主线节点不存在");
            }
        } else {
            Long dup = dialogTrainingQaMapper.selectCount(
                    new LambdaQueryWrapper<DialogTrainingQa>()
                            .eq(DialogTrainingQa::getKbId, kbId)
                            .eq(DialogTrainingQa::getRemark, "flow:" + stepCode));
            if (dup != null && dup > 0) {
                throw new BizException("节点编号 " + stepCode + " 已存在");
            }
            row = new DialogTrainingQa();
            row.setKbId(kbId);
            row.setDataType(DialogTrainingDataType.QUALITY_SAMPLE);
            row.setWeight(BigDecimal.valueOf(5.0));
            row.setStatus(1);
        }

        row.setRemark("flow:" + stepCode);
        row.setQuestion("[主线" + stepCode + "]" + scene);
        row.setStandardAnswer(req.getScript().trim());
        if (req.getFlowOrder() != null) {
            row.setFlowOrder(req.getFlowOrder());
        } else if (row.getFlowOrder() == null) {
            row.setFlowOrder(nextFlowOrder(kbId));
        }

        if (row.getId() == null) {
            dialogTrainingQaMapper.insert(row);
        } else {
            dialogTrainingQaMapper.updateById(row);
        }
        dialogScriptPackRegistry.reloadFromDb();
        return toDto(row);
    }

    public void deleteStep(Integer id) {
        DialogTrainingQa row = dialogTrainingQaMapper.selectById(id);
        if (row == null || !isFlowRow(row)) {
            throw new BizException("主线节点不存在");
        }
        dialogTrainingQaMapper.deleteById(id);
        dialogScriptPackRegistry.reloadFromDb();
    }

    /** 批量更新排序（ids 顺序即 flow_order） */
    public void reorderSteps(int kbId, List<Integer> orderedIds) {
        if (orderedIds == null || orderedIds.isEmpty()) {
            return;
        }
        int order = 10;
        for (Integer id : orderedIds) {
            DialogTrainingQa row = dialogTrainingQaMapper.selectById(id);
            if (row == null || !isFlowRow(row) || !kbIdEquals(row, kbId)) {
                continue;
            }
            row.setFlowOrder(order);
            dialogTrainingQaMapper.updateById(row);
            order += 10;
        }
        dialogScriptPackRegistry.reloadFromDb();
    }

    public Map<String, Object> summary(int kbId) {
        List<MainFlowStepDto> steps = listSteps(kbId);
        long withAudio = steps.stream().filter(s -> Boolean.TRUE.equals(s.getAudioReady())).count();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kbId", kbId);
        m.put("totalSteps", steps.size());
        m.put("audioReadyCount", withAudio);
        m.put("mainFlowEnabled", dialogScriptPackRegistry.isMainFlowEnabled(kbId));
        m.put("stepOrder", dialogScriptPackRegistry.mainFlowStepOrder(kbId));
        return m;
    }

    private int nextFlowOrder(int kbId) {
        List<MainFlowStepDto> steps = listSteps(kbId);
        return steps.stream()
                .map(MainFlowStepDto::getFlowOrder)
                .filter(o -> o != null)
                .max(Integer::compareTo)
                .orElse(0) + 10;
    }

    private static boolean isFlowRow(DialogTrainingQa row) {
        return row.getRemark() != null && row.getRemark().trim().startsWith("flow:");
    }

    private static boolean kbIdEquals(DialogTrainingQa row, int kbId) {
        int kid = row.getKbId() != null ? row.getKbId() : DialogCallContextService.DEFAULT_KB_ID;
        return kid == kbId;
    }

    static String stepFromRemark(String remark) {
        if (!StringUtils.hasText(remark) || !remark.startsWith("flow:")) {
            return "";
        }
        return remark.substring(5).trim();
    }

    static String sceneFromQuestion(String question) {
        if (!StringUtils.hasText(question)) {
            return "";
        }
        Matcher m = MAIN_QUESTION.matcher(question.trim());
        return m.matches() ? m.group(2).trim() : question.trim();
    }

    private static MainFlowStepDto toDto(DialogTrainingQa row) {
        MainFlowStepDto dto = new MainFlowStepDto();
        dto.setId(row.getId());
        dto.setKbId(row.getKbId());
        dto.setStepCode(stepFromRemark(row.getRemark()));
        dto.setSceneName(sceneFromQuestion(row.getQuestion()));
        dto.setScript(row.getStandardAnswer());
        dto.setFlowOrder(row.getFlowOrder());
        dto.setAnswerWavPath(row.getAnswerWavPath());
        dto.setAudioUrl(DialogTrainingQaService.answerAudioUrl(row));
        dto.setAudioReady(StringUtils.hasText(row.getAnswerWavPath()));
        return dto;
    }
}
