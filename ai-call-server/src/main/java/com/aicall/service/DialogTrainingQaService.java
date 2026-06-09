package com.aicall.service;

import com.aicall.common.BizException;
import com.aicall.common.DialogTrainingDataType;
import com.aicall.config.DialogRagProperties;
import com.aicall.dto.DialogRagRetrieveResult;
import com.aicall.dto.DialogTrainingQaSaveRequest;
import com.aicall.entity.DialogTrainingQa;
import com.aicall.mapper.DialogTrainingQaMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class DialogTrainingQaService {

    private final DialogTrainingQaMapper dialogTrainingQaMapper;
    private final DialogRagRetrievalService dialogRagRetrievalService;
    private final DialogRagProperties dialogRagProperties;
    private final DialogScriptPackRegistry dialogScriptPackRegistry;

    public List<DialogTrainingQa> list(Integer kbId, Integer dataType, Integer status) {
        LambdaQueryWrapper<DialogTrainingQa> q = new LambdaQueryWrapper<DialogTrainingQa>()
                .orderByDesc(DialogTrainingQa::getId);
        if (kbId != null) {
            q.eq(DialogTrainingQa::getKbId, kbId);
        }
        if (dataType != null) {
            q.eq(DialogTrainingQa::getDataType, dataType);
        }
        if (status != null) {
            q.eq(DialogTrainingQa::getStatus, status);
        }
        return dialogTrainingQaMapper.selectList(q);
    }

    public DialogTrainingQa get(Integer id) {
        return dialogTrainingQaMapper.selectById(id);
    }

    public DialogTrainingQa save(DialogTrainingQaSaveRequest req) {
        validate(req);
        DialogTrainingQa row;
        if (req.getId() != null) {
            row = dialogTrainingQaMapper.selectById(req.getId());
            if (row == null) {
                throw new BizException("记录不存在");
            }
        } else {
            row = new DialogTrainingQa();
        }
        row.setKbId(req.getKbId() != null ? req.getKbId() : DialogCallContextService.DEFAULT_KB_ID);
        row.setQuestion(req.getQuestion().trim());
        row.setStandardAnswer(req.getStandardAnswer().trim());
        row.setDataType(req.getDataType() != null ? req.getDataType() : DialogTrainingDataType.MANUAL_CORRECTION);
        row.setWeight(req.getWeight() != null ? req.getWeight()
                : DialogRagRetrievalService.defaultWeightForType(row.getDataType(), dialogRagProperties));
        row.setStatus(req.getStatus() != null ? req.getStatus() : 1);
        row.setSourceCallId(req.getSourceCallId());
        row.setRemark(req.getRemark());
        if (row.getId() == null) {
            dialogTrainingQaMapper.insert(row);
        } else {
            dialogTrainingQaMapper.updateById(row);
        }
        if (row.getStatus() != null && row.getStatus() == 1) {
            dialogRagRetrievalService.indexOne(row);
        } else {
            dialogRagRetrievalService.removeFromIndex(row.getId());
        }
        dialogScriptPackRegistry.reloadFromDb();
        return row;
    }

    public void delete(Integer id) {
        dialogTrainingQaMapper.deleteById(id);
        dialogRagRetrievalService.removeFromIndex(id);
        dialogScriptPackRegistry.reloadFromDb();
    }

    public void rebuildIndex() {
        dialogRagRetrievalService.rebuildIndex();
    }

    public DialogRagRetrieveResult testRetrieve(String question, Integer kbId) {
        int kid = kbId != null ? kbId : DialogCallContextService.DEFAULT_KB_ID;
        return dialogRagRetrievalService.retrieve(question, kid);
    }

    public Map<String, Object> stats(Integer kbId) {
        int kid = kbId != null ? kbId : DialogCallContextService.DEFAULT_KB_ID;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", dialogRagProperties.isEnabled());
        m.put("kbId", kid);
        m.put("indexSize", dialogRagRetrievalService.indexSizeByKb(kid));
        m.put("total", dialogTrainingQaMapper.selectCount(
                new LambdaQueryWrapper<DialogTrainingQa>()
                        .eq(DialogTrainingQa::getKbId, kid)
                        .eq(DialogTrainingQa::getStatus, 1)));
        m.put("manualCorrection", countByType(kid, DialogTrainingDataType.MANUAL_CORRECTION));
        m.put("qualitySample", countByType(kid, DialogTrainingDataType.QUALITY_SAMPLE));
        m.put("negative", countByType(kid, DialogTrainingDataType.NEGATIVE));
        m.put("mainFlowSize", dialogScriptPackRegistry.mainFlowSize(kid));
        m.put("fallbackRuleSize", dialogScriptPackRegistry.fallbackRuleSize(kid));
        m.put("directAnswerScore", dialogRagProperties.getDirectAnswerScore());
        m.put("minRetrieveScore", dialogRagProperties.getMinRetrieveScore());
        return m;
    }

    public int batchImport(List<DialogTrainingQaSaveRequest> items) {
        if (items == null || items.isEmpty()) {
            return 0;
        }
        int n = 0;
        for (DialogTrainingQaSaveRequest item : items) {
            save(item);
            n++;
        }
        return n;
    }

    public boolean existsQuestion(String question, Integer kbId) {
        if (!StringUtils.hasText(question)) {
            return false;
        }
        int kid = kbId != null ? kbId : DialogCallContextService.DEFAULT_KB_ID;
        Long n = dialogTrainingQaMapper.selectCount(
                new LambdaQueryWrapper<DialogTrainingQa>()
                        .eq(DialogTrainingQa::getKbId, kid)
                        .eq(DialogTrainingQa::getQuestion, question.trim())
                        .eq(DialogTrainingQa::getStatus, 1));
        return n != null && n > 0;
    }

    private long countByType(int kbId, int type) {
        return dialogTrainingQaMapper.selectCount(
                new LambdaQueryWrapper<DialogTrainingQa>()
                        .eq(DialogTrainingQa::getKbId, kbId)
                        .eq(DialogTrainingQa::getDataType, type)
                        .eq(DialogTrainingQa::getStatus, 1));
    }

    private void validate(DialogTrainingQaSaveRequest req) {
        if (req == null || !StringUtils.hasText(req.getQuestion()) || !StringUtils.hasText(req.getStandardAnswer())) {
            throw new BizException("问题与标准答案不能为空");
        }
        if (req.getQuestion().trim().length() > 500) {
            throw new BizException("问题不超过500字");
        }
        if (req.getStandardAnswer().trim().length() > 2000) {
            throw new BizException("标准答案不超过2000字");
        }
    }
}
