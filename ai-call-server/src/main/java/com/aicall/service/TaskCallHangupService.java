package com.aicall.service;

import com.aicall.common.CallStatus;
import com.aicall.common.ForcedHangupRules;
import com.aicall.common.HangupType;
import com.aicall.entity.CallRecord;
import com.aicall.mapper.CallRecordMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 任务暂停/终止时，挂断该任务下所有进行中的外呼通道。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskCallHangupService {

    private static final String KEY_RECORD_BY_UUID = "fs:call-record-id:";

    private final CallRecordMapper callRecordMapper;
    private final FreeSwitchEslService eslService;
    private final FreeSwitchDialService freeSwitchDialService;
    private final OutboundCallPendingService outboundCallPendingService;
    private final CallSessionService callSessionService;
    private final ForcedHangupService forcedHangupService;
    private final VoicePlaybackService voicePlaybackService;
    private final StringRedisTemplate redisTemplate;
    private final CallDialogPersistService callDialogPersistService;
    private final CallEndSummaryService callEndSummaryService;
    private final TtsFailureRecoveryService ttsFailureRecoveryService;

    /**
     * @param paused true=暂停，false=终止
     * @return 挂断的通道数量
     */
    public int hangupAllForTask(Integer taskId, boolean paused) {
        if (taskId == null) {
            return 0;
        }
        String hangupType = paused ? HangupType.TASK_PAUSED : HangupType.TASK_TERMINATED;
        int n = 0;
        n += hangupPendingCalls(taskId, hangupType);
        n += hangupInProgressRecords(taskId, hangupType);
        if (n > 0) {
            log.info("[任务挂断] taskId={} 已挂断通道数={} 原因={}", taskId, n, hangupType);
        }
        return n;
    }

    private int hangupPendingCalls(Integer taskId, String hangupType) {
        int count = 0;
        for (OutboundCallPendingService.PendingCall p : outboundCallPendingService.listByTaskId(taskId)) {
            if (p == null || !StringUtils.hasText(p.getFsUuid())) {
                continue;
            }
            String uuid = p.getFsUuid();
            try {
                settlePending(uuid, hangupType);
                killChannel(uuid);
                count++;
            } catch (Exception e) {
                log.warn("[任务挂断] 待接通通道处理失败 uuid={} taskId={}: {}", uuid, taskId, e.getMessage());
            } finally {
                outboundCallPendingService.remove(uuid);
            }
        }
        return count;
    }

    private void settlePending(String fsUuid, String hangupType) {
        OutboundCallPendingService.PendingCall p = outboundCallPendingService.get(fsUuid);
        if (p == null) {
            return;
        }
        CallSessionService.EndReq end = new CallSessionService.EndReq();
        end.setFsUuid(fsUuid);
        end.setCallStatus(CallStatus.NO_ANSWER);
        end.setCallDuration(0);
        end.setHangupType(hangupType);
        try {
            callSessionService.endSession(end);
        } catch (Exception e) {
            log.debug("[任务挂断] 待接通结算跳过 uuid={}: {}", fsUuid, e.getMessage());
        }
    }

    private int hangupInProgressRecords(Integer taskId, String hangupType) {
        List<CallRecord> active = callRecordMapper.selectList(
                new LambdaQueryWrapper<CallRecord>()
                        .eq(CallRecord::getTaskId, taskId)
                        .eq(CallRecord::getCallStatus, CallStatus.IN_PROGRESS));
        if (active.isEmpty()) {
            return 0;
        }
        Set<Integer> targetIds = new HashSet<>();
        for (CallRecord r : active) {
            targetIds.add(r.getId());
        }
        int count = 0;
        Set<String> uuids = findUuidsForRecordIds(targetIds);
        for (CallRecord record : active) {
            String uuid = resolveUuid(record.getId(), uuids);
            try {
                boolean answered = StringUtils.hasText(uuid) && eslService.isChannelAnswered(uuid);
                if (StringUtils.hasText(uuid)) {
                    voicePlaybackService.stopChannelPlayback(uuid);
                    if (answered && eslService.uuidExists(uuid)) {
                        ttsFailureRecoveryService.playEndingThenHangup(
                                uuid, record.getId(), ForcedHangupRules.END_WORDS, hangupType);
                    } else {
                        killChannel(uuid);
                    }
                } else {
                    log.warn("[任务挂断] 未找到 fsUuid recordId={} taskId={}", record.getId(), taskId);
                }
                settleInProgress(record, uuid, hangupType, answered);
                count++;
            } catch (Exception e) {
                log.warn("[任务挂断] 进行中通话处理失败 recordId={} taskId={}: {}",
                        record.getId(), taskId, e.getMessage());
            }
        }
        return count;
    }

    private void settleInProgress(CallRecord record, String fsUuid, String hangupType, boolean answered) {
        CallSessionService.EndReq end = new CallSessionService.EndReq();
        end.setCallRecordId(record.getId());
        end.setFsUuid(fsUuid);
        int duration = forcedHangupService.elapsedSeconds(record.getId(), null);
        end.setCallDuration(Math.max(0, duration));
        end.setCallStatus(answered ? CallStatus.CONNECTED : CallStatus.NO_ANSWER);
        end.setHangupType(hangupType);
        if (answered) {
            String dialog = callDialogPersistService.getDialogText(record.getId());
            end.setDialogText(dialog);
            callEndSummaryService.fillEndReqFromDialog(end, dialog, end.getCallDuration());
        }
        try {
            callSessionService.endSession(end);
        } catch (Exception e) {
            log.debug("[任务挂断] 通话已结束 recordId={}: {}", record.getId(), e.getMessage());
        }
        if (StringUtils.hasText(fsUuid)) {
            redisTemplate.delete(KEY_RECORD_BY_UUID + fsUuid);
        }
    }

    private void killChannel(String fsUuid) {
        if (!StringUtils.hasText(fsUuid)) {
            return;
        }
        if (eslService.uuidExists(fsUuid)) {
            freeSwitchDialService.hangup(fsUuid);
        }
    }

    private String resolveUuid(Integer recordId, Set<String> uuidCandidates) {
        for (String uuid : uuidCandidates) {
            String mapped = redisTemplate.opsForValue().get(KEY_RECORD_BY_UUID + uuid);
            if (mapped != null && mapped.equals(String.valueOf(recordId))) {
                return uuid;
            }
        }
        return null;
    }

    private Set<String> findUuidsForRecordIds(Set<Integer> recordIds) {
        Set<String> uuids = new HashSet<>();
        Set<String> keys = redisTemplate.keys(KEY_RECORD_BY_UUID + "*");
        if (keys == null || keys.isEmpty()) {
            return uuids;
        }
        for (String key : keys) {
            String val = redisTemplate.opsForValue().get(key);
            if (!StringUtils.hasText(val)) {
                continue;
            }
            try {
                if (recordIds.contains(Integer.parseInt(val.trim()))) {
                    uuids.add(key.substring(KEY_RECORD_BY_UUID.length()));
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return uuids;
    }
}
