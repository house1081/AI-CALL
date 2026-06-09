package com.aicall.service;

import com.aicall.common.BizException;
import com.aicall.common.CallStatus;
import com.aicall.entity.*;
import com.aicall.mapper.*;
import com.aicall.util.BillingUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * PRD 4-13 第二章计费规则（后端写死）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BillingService {

    private final TenantMapper tenantMapper;
    private final LineMapper lineMapper;
    private final CallRecordMapper callRecordMapper;
    private final BalanceLogMapper balanceLogMapper;
    private final CallTaskMapper callTaskMapper;
    private final NoticeService noticeService;
    private final StatisticsService statisticsService;

    /** 通话结束结算：接通计费，扣除「总费用 - 已实时预扣」；有 id 则 update（FS 会话） */
    @Transactional
    public void finishCall(CallRecord record, Tenant tenant, Line line) {
        boolean updating = record.getId() != null;
        CallRecord target = updating ? callRecordMapper.selectById(record.getId()) : record;
        if (updating && target == null) {
            throw new BizException("通话记录不存在");
        }
        if (updating) {
            mergeSessionFields(target, record);
            record = target;
        }

        if (!CallStatus.isConnected(record.getCallStatus())) {
            record.setDeductAmount(BigDecimal.ZERO);
            record.setCostAmount(BigDecimal.ZERO);
            record.setProfit(BigDecimal.ZERO);
            record.setBilledMinutes(0);
            record.setProfitAbnormal(0);
            if (!updating) {
                record.setPrepaidAmount(BigDecimal.ZERO);
            }
            persistRecord(record, updating);
            return;
        }

        int minutes = BillingUtil.ceilMinutes(record.getCallDuration());
        record.setBilledMinutes(minutes);
        record.setSellPriceSnapshot(tenant.getSellPrice());
        record.setCostPriceSnapshot(line.getCostPrice());

        BigDecimal totalDeduct = BillingUtil.calcAmount(minutes, tenant.getSellPrice());
        BigDecimal totalCost = BillingUtil.calcAmount(minutes, line.getCostPrice());
        BigDecimal prepaid = record.getPrepaidAmount() != null ? record.getPrepaidAmount() : BigDecimal.ZERO;
        BigDecimal remainDeduct = totalDeduct.subtract(prepaid).max(BigDecimal.ZERO);

        record.setDeductAmount(totalDeduct);
        record.setCostAmount(totalCost);
        BigDecimal profit = totalDeduct.subtract(totalCost).setScale(4, RoundingMode.HALF_UP);
        record.setProfit(profit);
        record.setProfitAbnormal(profit.compareTo(BigDecimal.ZERO) < 0 ? 1 : 0);

        persistRecord(record, updating);

        if (remainDeduct.compareTo(BigDecimal.ZERO) > 0) {
            settleRemainder(tenant.getId(), remainDeduct, record);
        } else if (prepaid.compareTo(totalDeduct) > 0) {
            log.warn("预扣金额大于应扣总额 tenant={} phone={}", tenant.getId(), record.getCustomerPhone());
        }

        statisticsService.refreshDailySnapshot(record.getCallTime().toLocalDate(), record.getTenantId(), record.getLineId());
        if (record.getProfitAbnormal() != null && record.getProfitAbnormal() == 1) {
            log.warn("负毛利通话 recordId={} tenant={} profit={}", record.getId(), tenant.getId(), profit);
        }
    }

    private void mergeSessionFields(CallRecord target, CallRecord incoming) {
        if (incoming.getCallDuration() != null) target.setCallDuration(incoming.getCallDuration());
        if (incoming.getCallStatus() != null) target.setCallStatus(incoming.getCallStatus());
        if (incoming.getRecordUrl() != null) target.setRecordUrl(incoming.getRecordUrl());
        if (incoming.getDialogText() != null) target.setDialogText(incoming.getDialogText());
        if (incoming.getCustomerNeed() != null) target.setCustomerNeed(incoming.getCustomerNeed());
        if (incoming.getCustomerPain() != null) target.setCustomerPain(incoming.getCustomerPain());
        if (incoming.getBudget() != null) target.setBudget(incoming.getBudget());
        if (incoming.getNextTime() != null) target.setNextTime(incoming.getNextTime());
        if (incoming.getLevel() != null) target.setLevel(incoming.getLevel());
        if (incoming.getHangupType() != null) target.setHangupType(incoming.getHangupType());
    }

    private void persistRecord(CallRecord record, boolean updating) {
        if (updating) {
            callRecordMapper.updateById(record);
        } else {
            callRecordMapper.insert(record);
        }
    }

    /** PRD 规则3：每满60秒实时扣1分钟费用，余额不足返回 false 触发挂断 */
    @Transactional
    public boolean deductRealtimeMinute(Integer tenantId, BigDecimal sellPrice, String phone, CallRecord record) {
        Tenant tenant = tenantMapper.selectById(tenantId);
        if (tenant.getBalance().compareTo(sellPrice) < 0) {
            pauseTenantTasks(tenantId);
            noticeService.notifyBalanceLow(tenant);
            return false;
        }
        Integer refId = record != null ? record.getId() : null;
        applyDeduction(tenantId, sellPrice, 2, "通话实时扣费(满1分钟) " + phone, refId);
        if (record != null && record.getId() != null) {
            CallRecord fresh = callRecordMapper.selectById(record.getId());
            BigDecimal prepaid = fresh.getPrepaidAmount() != null ? fresh.getPrepaidAmount() : BigDecimal.ZERO;
            fresh.setPrepaidAmount(prepaid.add(sellPrice));
            callRecordMapper.updateById(fresh);
            record.setPrepaidAmount(fresh.getPrepaidAmount());
        } else if (record != null) {
            BigDecimal prepaid = record.getPrepaidAmount() != null ? record.getPrepaidAmount() : BigDecimal.ZERO;
            record.setPrepaidAmount(prepaid.add(sellPrice));
        }
        return true;
    }

    /** 通话结束补扣剩余（余额不足则记入待补扣） */
    private void settleRemainder(Integer tenantId, BigDecimal remainDeduct, CallRecord record) {
        Tenant tenant = tenantMapper.selectById(tenantId);
        BigDecimal balance = tenant.getBalance();
        if (balance.compareTo(remainDeduct) >= 0) {
            applyDeduction(tenantId, remainDeduct, 2,
                    "通话结算扣费 " + record.getCustomerPhone(), record.getId());
            return;
        }
        if (balance.compareTo(BigDecimal.ZERO) > 0) {
            applyDeduction(tenantId, balance, 2,
                    "通话部分扣费 " + record.getCustomerPhone(), record.getId());
            BigDecimal shortfall = remainDeduct.subtract(balance).setScale(4, RoundingMode.HALF_UP);
            addPendingDeduct(tenantId, shortfall);
        } else {
            addPendingDeduct(tenantId, remainDeduct);
        }
        pauseTenantTasks(tenantId);
        noticeService.notifyBalanceLow(tenantMapper.selectById(tenantId));
    }

    private void addPendingDeduct(Integer tenantId, BigDecimal amount) {
        Tenant t = tenantMapper.selectById(tenantId);
        BigDecimal pending = t.getPendingDeduct() != null ? t.getPendingDeduct() : BigDecimal.ZERO;
        t.setPendingDeduct(pending.add(amount));
        tenantMapper.updateById(t);
    }

    @Transactional
    public void applyDeduction(Integer tenantId, BigDecimal amount, int logType, String remark, Integer refId) {
        Tenant fresh = tenantMapper.selectById(tenantId);
        BigDecimal deduct2 = amount.setScale(2, RoundingMode.HALF_UP);
        BigDecimal newBalance = fresh.getBalance().subtract(deduct2);
        BigDecimal pending = fresh.getPendingDeduct() != null ? fresh.getPendingDeduct() : BigDecimal.ZERO;

        if (newBalance.compareTo(BigDecimal.ZERO) < 0) {
            pending = pending.add(newBalance.abs()).setScale(4, RoundingMode.HALF_UP);
            newBalance = BigDecimal.ZERO;
        }

        fresh.setBalance(newBalance);
        fresh.setPendingDeduct(pending);
        tenantMapper.updateById(fresh);

        BalanceLog log = new BalanceLog();
        log.setTenantId(tenantId);
        log.setType(logType);
        log.setAmount(deduct2.negate());
        log.setBalanceAfter(newBalance);
        log.setRemark(remark);
        log.setRefId(refId);
        balanceLogMapper.insert(log);

        if (newBalance.compareTo(fresh.getSellPrice()) < 0) {
            pauseTenantTasks(tenantId);
            noticeService.notifyBalanceLow(fresh);
        }
    }

    @Transactional
    public void settlePendingDeduct(Integer tenantId) {
        Tenant tenant = tenantMapper.selectById(tenantId);
        if (tenant.getPendingDeduct() == null || tenant.getPendingDeduct().compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        BigDecimal pending = tenant.getPendingDeduct();
        BigDecimal canPay = tenant.getBalance().min(pending).setScale(2, RoundingMode.DOWN);
        if (canPay.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        tenant.setBalance(tenant.getBalance().subtract(canPay));
        tenant.setPendingDeduct(pending.subtract(canPay).max(BigDecimal.ZERO));
        tenantMapper.updateById(tenant);

        BalanceLog log = new BalanceLog();
        log.setTenantId(tenantId);
        log.setType(3);
        log.setAmount(canPay.negate());
        log.setBalanceAfter(tenant.getBalance());
        log.setRemark("待补扣自动结算");
        balanceLogMapper.insert(log);
    }

    public void assertCanStartTask(Tenant tenant) {
        if (tenant.getPendingDeduct() != null && tenant.getPendingDeduct().compareTo(BigDecimal.ZERO) > 0) {
            throw new BizException("存在待补扣金额 " + tenant.getPendingDeduct().setScale(2, RoundingMode.HALF_UP) + " 元，请先充值");
        }
        if (tenant.getBalance().compareTo(tenant.getSellPrice()) < 0) {
            throw new BizException("余额不足，请先充值");
        }
    }

    public void pauseTenantTasks(Integer tenantId) {
        callTaskMapper.selectList(new LambdaQueryWrapper<CallTask>()
                .eq(CallTask::getTenantId, tenantId)
                .eq(CallTask::getStatus, 1))
                .forEach(t -> {
                    t.setStatus(2);
                    callTaskMapper.updateById(t);
                });
    }

    public BigDecimal minLineCost() {
        return lineMapper.selectList(new LambdaQueryWrapper<Line>().eq(Line::getStatus, 1))
                .stream().map(Line::getCostPrice).min(BigDecimal::compareTo)
                .orElse(new BigDecimal("0.0600"));
    }

    public void validateSellPrice(BigDecimal sellPrice) {
        BigDecimal min = minLineCost();
        if (sellPrice.compareTo(min) <= 0) {
            throw new BizException("售价不可低于线路最低成本价，当前最低成本价为" + min + "元/分钟");
        }
        if (sellPrice.compareTo(new BigDecimal("0.0800")) < 0
                || sellPrice.compareTo(new BigDecimal("0.2000")) > 0) {
            throw new BizException("售价范围须在0.0800-0.2000元/分钟之间");
        }
    }

    /** 商户展示用：扣费金额保留2位小数 */
    public static BigDecimal displayDeduct(BigDecimal amount) {
        return amount == null ? BigDecimal.ZERO : amount.setScale(2, RoundingMode.HALF_UP);
    }
}
