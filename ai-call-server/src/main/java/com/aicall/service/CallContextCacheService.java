package com.aicall.service;

import com.aicall.dto.DialogCallContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;

/** 通话级对话上下文快照，避免每轮 LLM 重复查库 */
@Slf4j
@Service
public class CallContextCacheService {

    private final DialogCallContextService dialogCallContextService;
    private final ConcurrentHashMap<Integer, DialogCallContext> cache = new ConcurrentHashMap<>();

    public CallContextCacheService(DialogCallContextService dialogCallContextService) {
        this.dialogCallContextService = dialogCallContextService;
    }

    public DialogCallContext bind(Integer callRecordId) {
        if (callRecordId == null) {
            return dialogCallContextService.resolve(null);
        }
        return cache.computeIfAbsent(callRecordId, id -> {
            DialogCallContext ctx = dialogCallContextService.resolveAndSnapshot(id);
            log.debug("[对话上下文] 已快照 recordId={} kbId={}", id, ctx.getKbId());
            return ctx;
        });
    }

    public DialogCallContext get(Integer callRecordId) {
        if (callRecordId == null) {
            return dialogCallContextService.resolve(null);
        }
        DialogCallContext ctx = cache.get(callRecordId);
        return ctx != null ? ctx : dialogCallContextService.resolve(callRecordId);
    }

    public void unbind(Integer callRecordId) {
        if (callRecordId != null) {
            cache.remove(callRecordId);
        }
    }
}
