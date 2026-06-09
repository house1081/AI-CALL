package com.aicall.service;

import com.aicall.common.BizException;
import com.aicall.common.DialogTrainingDataType;
import com.aicall.config.DialogRagProperties;
import com.aicall.dto.DialogCallContext;
import com.aicall.entity.AiPrompt;
import com.aicall.entity.CallRecord;
import com.aicall.entity.CallTask;
import com.aicall.entity.DialogKnowledgeBase;
import com.aicall.entity.Tenant;
import com.aicall.mapper.AiPromptMapper;
import com.aicall.mapper.CallRecordMapper;
import com.aicall.mapper.CallTaskMapper;
import com.aicall.mapper.DialogKnowledgeBaseMapper;
import com.aicall.mapper.TenantMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** 外呼对话上下文：任务/商户 → 话术模板 → 训练知识库 */
@Service
@RequiredArgsConstructor
public class DialogCallContextService {

    public static final int DEFAULT_KB_ID = 1;

    private final CallRecordMapper callRecordMapper;
    private final CallTaskMapper callTaskMapper;
    private final TenantMapper tenantMapper;
    private final AiPromptMapper aiPromptMapper;
    private final DialogKnowledgeBaseMapper dialogKnowledgeBaseMapper;

    public DialogCallContext resolve(Integer callRecordId) {
        DialogCallContext ctx = new DialogCallContext();
        ctx.setCallRecordId(callRecordId);
        if (callRecordId == null) {
            fillGlobalDefault(ctx);
            return ctx;
        }
        CallRecord record = callRecordMapper.selectById(callRecordId);
        if (record == null) {
            fillGlobalDefault(ctx);
            return ctx;
        }
        ctx.setTenantId(record.getTenantId());
        ctx.setTaskId(record.getTaskId());
        if (record.getPromptId() != null && record.getKbId() != null) {
            ctx.setPromptId(record.getPromptId());
            ctx.setKbId(record.getKbId());
            ctx.setPrompt(loadPrompt(record.getPromptId()));
            return ctx;
        }
        resolveFromTenantTask(ctx, record.getTenantId(), record.getTaskId());
        return ctx;
    }

    /** 接通时快照模板与知识库到通话记录 */
    public DialogCallContext resolveAndSnapshot(Integer callRecordId) {
        CallRecord record = callRecordMapper.selectById(callRecordId);
        if (record == null) {
            throw new BizException("通话记录不存在");
        }
        DialogCallContext ctx = new DialogCallContext();
        ctx.setCallRecordId(callRecordId);
        ctx.setTenantId(record.getTenantId());
        ctx.setTaskId(record.getTaskId());
        resolveFromTenantTask(ctx, record.getTenantId(), record.getTaskId());
        if (record.getPromptId() == null || record.getKbId() == null) {
            record.setPromptId(ctx.getPromptId());
            record.setKbId(ctx.getKbId());
            callRecordMapper.updateById(record);
        }
        return ctx;
    }

    public DialogCallContext resolveForTenantTask(Integer tenantId, Integer taskId) {
        DialogCallContext ctx = new DialogCallContext();
        ctx.setTenantId(tenantId);
        ctx.setTaskId(taskId);
        resolveFromTenantTask(ctx, tenantId, taskId);
        return ctx;
    }

    private void resolveFromTenantTask(DialogCallContext ctx, Integer tenantId, Integer taskId) {
        Integer promptId = null;
        if (taskId != null) {
            CallTask task = callTaskMapper.selectById(taskId);
            if (task != null && task.getPromptId() != null) {
                promptId = task.getPromptId();
            }
        }
        if (promptId == null && tenantId != null) {
            Tenant tenant = tenantMapper.selectById(tenantId);
            if (tenant != null && tenant.getPromptId() != null) {
                promptId = tenant.getPromptId();
            }
        }
        AiPrompt prompt = promptId != null ? loadPrompt(promptId) : loadActivePrompt();
        if (prompt == null) {
            ctx.setKbId(DEFAULT_KB_ID);
            return;
        }
        ctx.setPromptId(prompt.getId());
        ctx.setPrompt(prompt);
        ctx.setKbId(prompt.getKbId() != null ? prompt.getKbId() : DEFAULT_KB_ID);
    }

    private void fillGlobalDefault(DialogCallContext ctx) {
        AiPrompt prompt = loadActivePrompt();
        if (prompt != null) {
            ctx.setPromptId(prompt.getId());
            ctx.setPrompt(prompt);
            ctx.setKbId(prompt.getKbId() != null ? prompt.getKbId() : DEFAULT_KB_ID);
        } else {
            ctx.setKbId(DEFAULT_KB_ID);
        }
    }

    public AiPrompt loadPrompt(Integer promptId) {
        if (promptId == null) {
            return null;
        }
        return aiPromptMapper.selectById(promptId);
    }

    public AiPrompt loadActivePrompt() {
        return aiPromptMapper.selectOne(
                new LambdaQueryWrapper<AiPrompt>().eq(AiPrompt::getIsActive, 1).last("LIMIT 1"));
    }

    public DialogKnowledgeBase requireKb(Integer kbId) {
        DialogKnowledgeBase kb = dialogKnowledgeBaseMapper.selectById(kbId);
        if (kb == null || kb.getStatus() == null || kb.getStatus() != 1) {
            throw new BizException("知识库不存在或已停用: " + kbId);
        }
        return kb;
    }

    public boolean kbHasMainFlow(Integer kbId) {
        if (kbId == null) {
            return false;
        }
        DialogKnowledgeBase kb = dialogKnowledgeBaseMapper.selectById(kbId);
        return kb != null && "loan".equalsIgnoreCase(kb.getPackType());
    }
}
