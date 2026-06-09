package com.aicall.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class OutboundCallPendingService {

    private static final String KEY_PREFIX = "fs:outbound:pending:";
    private static final Duration TTL = Duration.ofHours(4);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    private static final String LATEST_KEY = "fs:outbound:latest";

    public void register(String fsUuid, FreeSwitchDialService.DialRequest req) {
        if (!StringUtils.hasText(fsUuid) || req == null) {
            return;
        }
        PendingCall p = new PendingCall();
        p.setFsUuid(fsUuid);
        p.setTenantId(req.getTenantId());
        p.setLineId(req.getLineId());
        p.setTaskId(req.getTaskId());
        p.setCustomerId(req.getCustomerId());
        p.setCallee(req.getCallee());
        p.setMaxRingCount(req.getMaxRingCount());
        try {
            redisTemplate.opsForValue().set(KEY_PREFIX + fsUuid,
                    objectMapper.writeValueAsString(p), TTL);
            redisTemplate.opsForValue().set(LATEST_KEY, fsUuid, TTL);
        } catch (Exception e) {
            log.warn("登记外呼待处理会话失败 uuid={}: {}", fsUuid, e.getMessage());
        }
    }

    public PendingCall get(String fsUuid) {
        if (!StringUtils.hasText(fsUuid)) {
            return null;
        }
        try {
            String json = redisTemplate.opsForValue().get(KEY_PREFIX + fsUuid);
            if (!StringUtils.hasText(json)) {
                return null;
            }
            return objectMapper.readValue(json, PendingCall.class);
        } catch (Exception e) {
            log.warn("读取外呼待处理会话失败 uuid={}: {}", fsUuid, e.getMessage());
            return null;
        }
    }

    public PendingCall getLatest() {
        try {
            String uuid = redisTemplate.opsForValue().get(LATEST_KEY);
            return get(uuid);
        } catch (Exception e) {
            return null;
        }
    }

    public void remove(String fsUuid) {
        if (StringUtils.hasText(fsUuid)) {
            redisTemplate.delete(KEY_PREFIX + fsUuid);
        }
    }

    /** 任务暂停/终止时，找出尚未接通、仍在 FS 上的外呼 */
    public List<PendingCall> listByTaskId(Integer taskId) {
        List<PendingCall> list = new ArrayList<>();
        if (taskId == null) {
            return list;
        }
        Set<String> keys = redisTemplate.keys(KEY_PREFIX + "*");
        if (keys == null) {
            return list;
        }
        for (String key : keys) {
            PendingCall p = get(key.substring(KEY_PREFIX.length()));
            if (p != null && taskId.equals(p.getTaskId())) {
                list.add(p);
            }
        }
        return list;
    }

    @Data
    public static class PendingCall {
        private String fsUuid;
        private Integer tenantId;
        private Integer lineId;
        private Integer taskId;
        private Integer customerId;
        private String callee;
        private Integer maxRingCount;
    }
}
