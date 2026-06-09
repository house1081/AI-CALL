package com.aicall.service;

import com.aicall.entity.CallRecord;
import com.aicall.entity.Tenant;
import com.aicall.mapper.CallRecordMapper;
import com.aicall.mapper.TenantMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/**
 * PRD 2.4 盈利异常监控（仅管理员）
 */
@Service
@RequiredArgsConstructor
public class ProfitMonitorService {

    private final CallRecordMapper callRecordMapper;
    private final TenantMapper tenantMapper;

    public Map<String, Object> monitor() {
        LocalDate today = LocalDate.now();
        List<CallRecord> abnormal = callRecordMapper.selectList(
                new LambdaQueryWrapper<CallRecord>()
                        .eq(CallRecord::getProfitAbnormal, 1)
                        .ge(CallRecord::getCallTime, today.atStartOfDay())
                        .orderByDesc(CallRecord::getId)
                        .last("LIMIT 20"));

        List<Tenant> lowBalance = tenantMapper.selectList(new LambdaQueryWrapper<Tenant>()
                .eq(Tenant::getStatus, 1));
        List<Map<String, Object>> balanceAlerts = new ArrayList<>();
        for (Tenant t : lowBalance) {
            if (t.getBalance().compareTo(t.getSellPrice()) < 0) {
                Map<String, Object> m = new HashMap<>();
                m.put("tenantId", t.getId());
                m.put("username", t.getUsername());
                m.put("balance", t.getBalance());
                m.put("pendingDeduct", t.getPendingDeduct());
                balanceAlerts.add(m);
            }
            if (t.getPendingDeduct() != null && t.getPendingDeduct().compareTo(BigDecimal.ZERO) > 0) {
                Map<String, Object> m = new HashMap<>();
                m.put("tenantId", t.getId());
                m.put("username", t.getUsername());
                m.put("pendingDeduct", t.getPendingDeduct());
                m.put("type", "pending_deduct");
                balanceAlerts.add(m);
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("abnormalProfitCount", abnormal.size());
        result.put("abnormalProfitList", abnormal);
        result.put("balanceAlertCount", balanceAlerts.size());
        result.put("balanceAlerts", balanceAlerts);
        return result;
    }
}
