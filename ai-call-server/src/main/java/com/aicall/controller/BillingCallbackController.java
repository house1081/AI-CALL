package com.aicall.controller;

import com.aicall.common.Result;
import com.aicall.entity.CallRecord;
import com.aicall.entity.Tenant;
import com.aicall.mapper.TenantMapper;
import com.aicall.service.BillingService;
import com.aicall.service.CallSessionService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;

/**
 * PRD 第九章接口5：余额实时扣费（FreeSWITCH 通话中每分钟回调）
 */
@RestController
@RequestMapping("/api/callback/billing")
@RequiredArgsConstructor
public class BillingCallbackController {

    private final BillingService billingService;
    private final TenantMapper tenantMapper;
    private final CallSessionService callSessionService;

    @PostMapping("/deduct-minute")
    public Result<DeductResult> deductMinute(@RequestBody DeductReq req) {
        Tenant tenant = tenantMapper.selectById(req.getTenantId());
        CallRecord record = null;
        if (req.getCallRecordId() != null) {
            record = callSessionService.getSession(req.getCallRecordId());
        }
        boolean ok = billingService.deductRealtimeMinute(
                req.getTenantId(), tenant.getSellPrice(), req.getCustomerPhone(), record);
        DeductResult r = new DeductResult();
        r.setSuccess(ok);
        r.setBalance(tenantMapper.selectById(req.getTenantId()).getBalance());
        r.setShouldHangup(!ok);
        r.setPrepaidAmount(record != null ? record.getPrepaidAmount() : null);
        return Result.ok(r);
    }

    @Data
    public static class DeductReq {
        private Integer tenantId;
        private Integer lineId;
        private Integer callRecordId;
        private String customerPhone;
        private Integer elapsedSeconds;
    }

    @Data
    public static class DeductResult {
        private boolean success;
        private boolean shouldHangup;
        private BigDecimal balance;
        private BigDecimal prepaidAmount;
    }
}
