package com.aicall.controller.admin;

import com.aicall.common.BizException;
import com.aicall.common.Result;
import com.aicall.entity.BalanceLog;
import com.aicall.entity.RechargeOrder;
import com.aicall.entity.Tenant;
import com.aicall.mapper.BalanceLogMapper;
import com.aicall.mapper.RechargeOrderMapper;
import com.aicall.mapper.TenantMapper;
import com.aicall.service.BillingService;
import com.aicall.service.NoticeService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@RestController
@RequestMapping("/api/admin/recharge")
@RequiredArgsConstructor
public class AdminRechargeController {

    private final RechargeOrderMapper rechargeOrderMapper;
    private final TenantMapper tenantMapper;
    private final BalanceLogMapper balanceLogMapper;
    private final BillingService billingService;
    private final NoticeService noticeService;

    @PostMapping("/audit/{id}")
    public Result<Void> audit(@PathVariable Integer id,
                              @RequestParam Integer status,
                              @RequestParam(required = false) String failReason) {
        RechargeOrder order = rechargeOrderMapper.selectById(id);
        if (order == null || order.getStatus() != 0) {
            throw new BizException("订单状态异常");
        }
        if (status == 1) {
            Tenant tenant = tenantMapper.selectById(order.getTenantId());
            BigDecimal newBal = tenant.getBalance().add(order.getRechargeAmount());
            tenant.setBalance(newBal);
            tenantMapper.updateById(tenant);
            order.setArrivalBalance(newBal);
            order.setStatus(1);
            order.setAuditTime(LocalDateTime.now());
            rechargeOrderMapper.updateById(order);
            BalanceLog log = new BalanceLog();
            log.setTenantId(order.getTenantId());
            log.setType(1);
            log.setAmount(order.getRechargeAmount());
            log.setBalanceAfter(newBal);
            log.setRemark("充值到账 " + order.getOrderNo());
            log.setRefId(order.getId());
            balanceLogMapper.insert(log);
            billingService.settlePendingDeduct(order.getTenantId());
            noticeService.notifyRechargeResult(order.getTenantId(), true, order.getRechargeAmount(), null);
        } else {
            order.setStatus(2);
            order.setFailReason(failReason);
            order.setAuditTime(LocalDateTime.now());
            rechargeOrderMapper.updateById(order);
            noticeService.notifyRechargeResult(order.getTenantId(), false, order.getRechargeAmount(), failReason);
        }
        return Result.ok();
    }
}
