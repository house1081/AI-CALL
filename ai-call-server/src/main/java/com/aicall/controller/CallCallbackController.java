package com.aicall.controller;

import com.aicall.common.Result;
import com.aicall.entity.*;
import com.aicall.mapper.*;
import com.aicall.service.BillingService;
import com.aicall.service.CallSessionService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * FreeSWITCH / AI 层回调接口
 */
@RestController
@RequestMapping("/api/callback")
@RequiredArgsConstructor
public class CallCallbackController {

    private final TenantMapper tenantMapper;
    private final LineMapper lineMapper;
    private final BillingService billingService;
    private final CallSessionService callSessionService;
    private final CustomerMapper customerMapper;

    @PostMapping("/call-end")
    public Result<Void> callEnd(@RequestBody CallEndReq req) {
        if (req.getCallRecordId() != null) {
            CallSessionService.EndReq endReq = new CallSessionService.EndReq();
            endReq.setCallRecordId(req.getCallRecordId());
            endReq.setCallDuration(req.getCallDuration());
            endReq.setCallStatus(req.getCallStatus());
            endReq.setRecordUrl(req.getRecordUrl());
            endReq.setDialogText(req.getDialogText());
            endReq.setCustomerNeed(req.getCustomerNeed());
            endReq.setCustomerPain(req.getCustomerPain());
            endReq.setBudget(req.getBudget());
            endReq.setNextTime(req.getNextTime());
            endReq.setLevel(req.getLevel());
            callSessionService.endSession(endReq);
            updateCustomerLevel(req);
            return Result.ok();
        }
        Tenant tenant = tenantMapper.selectById(req.getTenantId());
        Line line = lineMapper.selectById(req.getLineId());
        CallRecord record = new CallRecord();
        record.setTenantId(req.getTenantId());
        record.setLineId(req.getLineId());
        record.setCustomerId(req.getCustomerId());
        record.setCustomerPhone(req.getCustomerPhone());
        record.setCallDuration(req.getCallDuration());
        record.setCallStatus(req.getCallStatus());
        record.setTaskId(req.getTaskId());
        record.setRecordUrl(req.getRecordUrl());
        record.setDialogText(req.getDialogText());
        record.setCustomerNeed(req.getCustomerNeed());
        record.setCustomerPain(req.getCustomerPain());
        record.setBudget(req.getBudget());
        record.setNextTime(req.getNextTime());
        record.setLevel(req.getLevel() != null ? req.getLevel() : "D");
        billingService.finishCall(record, tenant, line);
        updateCustomerLevel(req);
        return Result.ok();
    }

    private void updateCustomerLevel(CallEndReq req) {
        if (req.getCustomerId() != null && req.getLevel() != null) {
            Customer c = customerMapper.selectById(req.getCustomerId());
            if (c != null) {
                c.setLevel(req.getLevel());
                customerMapper.updateById(c);
            }
        }
    }

    @Data
    public static class CallEndReq {
        private Integer callRecordId;
        private Integer tenantId;
        private Integer lineId;
        private Integer customerId;
        private String customerPhone;
        private Integer callDuration;
        private Integer callStatus;
        private Integer taskId;
        private String recordUrl;
        private String dialogText;
        private String customerNeed;
        private String customerPain;
        private String budget;
        private String nextTime;
        private String level;
    }
}
