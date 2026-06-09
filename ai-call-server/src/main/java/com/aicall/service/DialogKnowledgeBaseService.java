package com.aicall.service;

import com.aicall.common.BizException;
import com.aicall.entity.DialogKnowledgeBase;
import com.aicall.entity.DialogTrainingQa;
import com.aicall.mapper.DialogKnowledgeBaseMapper;
import com.aicall.mapper.DialogTrainingQaMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class DialogKnowledgeBaseService {

    private final DialogKnowledgeBaseMapper dialogKnowledgeBaseMapper;
    private final DialogTrainingQaMapper dialogTrainingQaMapper;
    private final DialogRagRetrievalService dialogRagRetrievalService;
    private final DialogScriptPackRegistry dialogScriptPackRegistry;

    public List<DialogKnowledgeBase> list(Integer tenantId, Integer status) {
        LambdaQueryWrapper<DialogKnowledgeBase> q = new LambdaQueryWrapper<DialogKnowledgeBase>()
                .orderByAsc(DialogKnowledgeBase::getId);
        if (tenantId != null) {
            q.and(w -> w.isNull(DialogKnowledgeBase::getTenantId).or().eq(DialogKnowledgeBase::getTenantId, tenantId));
        }
        if (status != null) {
            q.eq(DialogKnowledgeBase::getStatus, status);
        }
        return dialogKnowledgeBaseMapper.selectList(q);
    }

    public DialogKnowledgeBase get(Integer id) {
        return dialogKnowledgeBaseMapper.selectById(id);
    }

    public DialogKnowledgeBase save(DialogKnowledgeBase kb) {
        if (kb == null || !StringUtils.hasText(kb.getKbName())) {
            throw new BizException("知识库名称不能为空");
        }
        if (!StringUtils.hasText(kb.getPackType())) {
            kb.setPackType("custom");
        }
        if (kb.getStatus() == null) {
            kb.setStatus(1);
        }
        if (kb.getId() == null) {
            dialogKnowledgeBaseMapper.insert(kb);
        } else {
            dialogKnowledgeBaseMapper.updateById(kb);
        }
        return kb;
    }

    public Map<String, Object> stats(Integer kbId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kbId", kbId);
        m.put("total", dialogTrainingQaMapper.selectCount(
                new LambdaQueryWrapper<DialogTrainingQa>()
                        .eq(DialogTrainingQa::getKbId, kbId)
                        .eq(DialogTrainingQa::getStatus, 1)));
        m.put("indexSize", dialogRagRetrievalService.indexSizeByKb(kbId));
        m.put("mainFlowSize", dialogScriptPackRegistry.mainFlowSize(kbId));
        m.put("fallbackRuleSize", dialogScriptPackRegistry.fallbackRuleSize(kbId));
        return m;
    }
}
