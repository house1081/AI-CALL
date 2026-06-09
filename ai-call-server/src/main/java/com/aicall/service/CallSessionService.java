package com.aicall.service;

import com.aicall.common.BizException;
import com.aicall.common.CallStatus;
import com.aicall.common.HangupType;
import com.aicall.config.AiVoiceProperties;
import com.aicall.config.FreeSwitchProperties;
import com.aicall.dto.DialogCallContext;
import com.aicall.entity.CallRecord;
import com.aicall.entity.Line;
import com.aicall.entity.Tenant;
import com.aicall.mapper.CallRecordMapper;
import com.aicall.mapper.LineMapper;
import com.aicall.mapper.TenantMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;

/**
 * FreeSWITCH 通话会话：接通前创建记录，结束时结算
 */
@Service
@RequiredArgsConstructor
public class CallSessionService {

    private static final String KEY_RECORD_BY_UUID = "fs:call-record-id:";
    private static final Duration RECORD_UUID_TTL = Duration.ofHours(4);

    private final CallRecordMapper callRecordMapper;
    private final TenantMapper tenantMapper;
    private final LineMapper lineMapper;
    private final BillingService billingService;
    private final ForcedHangupService forcedHangupService;
    private final OutboundCallPendingService outboundCallPendingService;
    private final CallTaskProgressService callTaskProgressService;
    private final CallAiVoiceService callAiVoiceService;
    private final StringRedisTemplate redisTemplate;
    private final OutboundCallWaitService outboundCallWaitService;
    private final CallEndSummaryService callEndSummaryService;
    private final FreeSwitchProperties freeSwitchProperties;
    private final DialogMainFlowService dialogMainFlowService;
    private final DialogCallContextService dialogCallContextService;
    private final TaskWechatAddService taskWechatAddService;
    private final AiVoiceProperties aiVoiceProperties;
    private final OutboundDialogRegistry outboundDialogRegistry;

    @Transactional
    public CallRecord startSession(StartReq req) {
        mergeFromPending(req);
        if (aiVoiceProperties.isDialogEnabled()) {
            req.setSkipOpeningVoice(true);
        }
        if (StringUtils.hasText(req.getFsUuid())) {
            String existingId = redisTemplate.opsForValue().get(KEY_RECORD_BY_UUID + req.getFsUuid());
            if (StringUtils.hasText(existingId)) {
                CallRecord existing = callRecordMapper.selectById(Integer.parseInt(existingId));
                if (existing != null) {
                    return existing;
                }
            }
        }
        Tenant tenant = tenantMapper.selectById(req.getTenantId());
        Line line = lineMapper.selectById(req.getLineId());
        if (tenant == null || line == null) {
            throw new BizException("商户或线路不存在");
        }
        billingService.assertCanStartTask(tenant);

        CallRecord record = new CallRecord();
        record.setTenantId(req.getTenantId());
        record.setLineId(req.getLineId());
        record.setCustomerId(req.getCustomerId());
        record.setCustomerPhone(req.getCustomerPhone());
        record.setTaskId(req.getTaskId());
        record.setCallStatus(CallStatus.IN_PROGRESS);
        record.setCallDuration(0);
        record.setBilledMinutes(0);
        record.setPrepaidAmount(BigDecimal.ZERO);
        record.setDeductAmount(BigDecimal.ZERO);
        record.setCostAmount(BigDecimal.ZERO);
        record.setProfit(BigDecimal.ZERO);
        record.setProfitAbnormal(0);
        record.setCallTime(LocalDateTime.now());
        record.setLevel("D");
        record.setHangupType(null);
        DialogCallContext dialogCtx = dialogCallContextService.resolveForTenantTask(req.getTenantId(), req.getTaskId());
        record.setPromptId(dialogCtx.getPromptId());
        record.setKbId(dialogCtx.getKbId());
        callRecordMapper.insert(record);
        if (StringUtils.hasText(req.getFsUuid())) {
            redisTemplate.opsForValue().set(
                    KEY_RECORD_BY_UUID + req.getFsUuid(),
                    String.valueOf(record.getId()),
                    RECORD_UUID_TTL);
        }
        forcedHangupService.initSession(record.getId(), null, req.getFsUuid());
        if (req.getTaskId() != null) {
            callTaskProgressService.onCallAnswered(req.getTaskId());
            taskWechatAddService.scheduleOnAnswered(
                    req.getTaskId(), record.getCustomerPhone(), record.getCustomerId());
        }
        if (req.getFsUuid() != null) {
            outboundCallPendingService.remove(req.getFsUuid());
            if (!Boolean.TRUE.equals(req.getSkipOpeningVoice())) {
                callAiVoiceService.onCallAnswered(req.getFsUuid(), record.getId());
            }
        }
        return record;
    }

    private void mergeFromPending(StartReq req) {
        if (!org.springframework.util.StringUtils.hasText(req.getFsUuid())) {
            return;
        }
        OutboundCallPendingService.PendingCall p = outboundCallPendingService.get(req.getFsUuid());
        if (p == null) {
            return;
        }
        if (req.getTenantId() == null) {
            req.setTenantId(p.getTenantId());
        }
        if (req.getLineId() == null) {
            req.setLineId(p.getLineId());
        }
        if (req.getTaskId() == null) {
            req.setTaskId(p.getTaskId());
        }
        if (req.getCustomerId() == null) {
            req.setCustomerId(p.getCustomerId());
        }
        if (!org.springframework.util.StringUtils.hasText(req.getCustomerPhone())) {
            req.setCustomerPhone(p.getCallee());
        }
    }

    @Transactional
    public void endSession(EndReq req) {
        if (req.getCallRecordId() == null && org.springframework.util.StringUtils.hasText(req.getFsUuid())) {
            endSessionByFsUuid(req);
            return;
        }
        CallRecord record = callRecordMapper.selectById(req.getCallRecordId());
        if (record == null) {
            throw new BizException("通话记录不存在");
        }
        if (record.getCallStatus() != null && record.getCallStatus() != CallStatus.IN_PROGRESS) {
            outboundCallWaitService.complete(req.getFsUuid(), record.getTaskId());
            if (record.getTaskId() != null && freeSwitchProperties.isEnabled()
                    && freeSwitchProperties.isTaskTerminateAfterCall()) {
                callTaskProgressService.terminateRunningTaskAfterCall(record.getTaskId());
            }
            return;
        }
        Tenant tenant = tenantMapper.selectById(record.getTenantId());
        Line line = lineMapper.selectById(record.getLineId());

        int duration = req.getCallDuration() != null ? req.getCallDuration() : 0;
        if (!StringUtils.hasText(req.getLevel()) && StringUtils.hasText(req.getDialogText())) {
            callEndSummaryService.fillEndReqFromDialog(req, req.getDialogText(), duration);
        } else if (!StringUtils.hasText(req.getLevel()) && StringUtils.hasText(record.getDialogText())) {
            callEndSummaryService.fillEndReqFromDialog(req, record.getDialogText(), duration);
        }

        record.setCallDuration(duration);
        record.setCallStatus(req.getCallStatus());
        record.setRecordUrl(req.getRecordUrl());
        record.setDialogText(req.getDialogText());
        record.setCustomerNeed(req.getCustomerNeed());
        record.setCustomerPain(req.getCustomerPain());
        record.setBudget(req.getBudget());
        record.setNextTime(req.getNextTime());
        if (req.getLevel() != null) {
            record.setLevel(req.getLevel());
        }
        if (req.getHangupType() != null) {
            record.setHangupType(req.getHangupType());
        } else if (CallStatus.isConnected(req.getCallStatus())) {
            record.setHangupType(HangupType.NORMAL);
        }
        billingService.finishCall(record, tenant, line);
        forcedHangupService.clearSession(req.getCallRecordId(), null);
        dialogMainFlowService.clearCall(req.getCallRecordId());
        if (record.getTaskId() != null) {
            callTaskProgressService.onCallFinished(record.getTaskId(), record, record.getCallStatus());
        }
        outboundCallWaitService.complete(req.getFsUuid(), record.getTaskId());
        if (StringUtils.hasText(req.getFsUuid())) {
            outboundDialogRegistry.cancel(req.getFsUuid());
            callAiVoiceService.releaseCallResources(req.getFsUuid());
            redisTemplate.delete(KEY_RECORD_BY_UUID + req.getFsUuid());
        }
    }

    public CallRecord getSession(Integer callRecordId) {
        CallRecord record = callRecordMapper.selectById(callRecordId);
        if (record == null) {
            throw new BizException("通话记录不存在");
        }
        return record;
    }

    @lombok.Data
    public static class StartReq {
        private Integer tenantId;
        private Integer lineId;
        private Integer customerId;
        private String customerPhone;
        private Integer taskId;
        private String fsUuid;
        /** socket 8888 模式由对话服务播开场白，跳过 onCallAnswered */
        private Boolean skipOpeningVoice;
    }

    /** 未接通仅挂断：仅传 fsUuid + callStatus */
    private void endSessionByFsUuid(EndReq req) {
        OutboundCallPendingService.PendingCall p = outboundCallPendingService.get(req.getFsUuid());
        if (p == null) {
            throw new BizException("未找到外呼会话 uuid=" + req.getFsUuid());
        }
        Tenant tenant = tenantMapper.selectById(p.getTenantId());
        Line line = lineMapper.selectById(p.getLineId());
        if (tenant == null || line == null) {
            throw new BizException("商户或线路不存在");
        }
        int status = req.getCallStatus() != null ? req.getCallStatus() : CallStatus.NO_ANSWER;
        CallRecord record = new CallRecord();
        record.setTenantId(p.getTenantId());
        record.setLineId(p.getLineId());
        record.setCustomerId(p.getCustomerId());
        record.setCustomerPhone(p.getCallee());
        record.setTaskId(p.getTaskId());
        record.setCallDuration(req.getCallDuration() != null ? req.getCallDuration() : 0);
        record.setCallStatus(status);
        record.setCallTime(java.time.LocalDateTime.now());
        record.setLevel("D");
        record.setHangupType(req.getHangupType());
        billingService.finishCall(record, tenant, line);
        outboundCallPendingService.remove(req.getFsUuid());
        if (p.getTaskId() != null) {
            callTaskProgressService.onCallFinished(p.getTaskId(), record, status);
        }
        outboundCallWaitService.complete(req.getFsUuid(), p.getTaskId());
        if (StringUtils.hasText(req.getFsUuid())) {
            outboundDialogRegistry.cancel(req.getFsUuid());
            callAiVoiceService.releaseCallResources(req.getFsUuid());
        }
    }

    @lombok.Data
    public static class EndReq {
        private Integer callRecordId;
        private String fsUuid;
        private Integer callDuration;
        private Integer callStatus;
        private String recordUrl;
        private String dialogText;
        private String customerNeed;
        private String customerPain;
        private String budget;
        private String nextTime;
        private String level;
        private String hangupType;
    }
}
