package com.aicall.service;

import com.aicall.dto.CallSessionMeta;
import com.aicall.entity.CallRecord;
import com.aicall.mapper.CallRecordMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
public class CallSessionMetaService {

    private final CallRecordMapper callRecordMapper;
    private final ConcurrentHashMap<Integer, CallSessionMeta> cache = new ConcurrentHashMap<>();

    public CallSessionMeta bind(Integer callRecordId) {
        if (callRecordId == null) {
            return null;
        }
        return cache.computeIfAbsent(callRecordId, id -> {
            CallRecord record = callRecordMapper.selectById(id);
            CallSessionMeta meta = new CallSessionMeta();
            meta.setCallRecordId(id);
            if (record != null) {
                meta.setTaskId(record.getTaskId());
                meta.setCustomerPhone(record.getCustomerPhone());
            }
            return meta;
        });
    }

    public CallSessionMeta get(Integer callRecordId) {
        return callRecordId != null ? cache.get(callRecordId) : null;
    }

    public void unbind(Integer callRecordId) {
        if (callRecordId != null) {
            cache.remove(callRecordId);
        }
    }
}
