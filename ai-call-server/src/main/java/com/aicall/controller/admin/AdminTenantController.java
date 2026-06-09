package com.aicall.controller.admin;

import com.aicall.common.BizException;
import com.aicall.common.PageResult;
import com.aicall.common.Result;
import com.aicall.entity.*;
import com.aicall.mapper.*;
import com.aicall.context.UserContext;
import com.aicall.service.BillingService;
import com.aicall.util.Md5Util;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ThreadLocalRandom;

@RestController
@RequestMapping("/api/admin/tenant")
@RequiredArgsConstructor
public class AdminTenantController {

    private final TenantMapper tenantMapper;
    private final PriceConfigMapper priceConfigMapper;
    private final RechargeOrderMapper rechargeOrderMapper;
    private final BalanceLogMapper balanceLogMapper;
    private final BillingService billingService;
    private final CustomerGroupMapper customerGroupMapper;
    private final PriceChangeLogMapper priceChangeLogMapper;

    @GetMapping("/list")
    public Result<PageResult<Tenant>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer status) {
        LambdaQueryWrapper<Tenant> q = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(keyword)) {
            q.and(w -> w.like(Tenant::getUsername, keyword)
                    .or().like(Tenant::getContactName, keyword)
                    .or().like(Tenant::getContactPhone, keyword));
        }
        if (status != null) {
            q.eq(Tenant::getStatus, status);
        }
        q.orderByDesc(Tenant::getId);
        Page<Tenant> p = tenantMapper.selectPage(new Page<>(page, pageSize), q);
        p.getRecords().forEach(t -> t.setPassword(null));
        return Result.ok(PageResult.of(p.getRecords(), p.getTotal(), page, pageSize));
    }

    @PostMapping("/save")
    public Result<Void> save(@RequestBody Tenant tenant) {
        billingService.validateSellPrice(tenant.getSellPrice());
        if (tenant.getId() == null) {
            long u = tenantMapper.selectCount(new LambdaQueryWrapper<Tenant>()
                    .eq(Tenant::getUsername, tenant.getUsername()));
            if (u > 0) throw new BizException("商户账号已存在");
            long p = tenantMapper.selectCount(new LambdaQueryWrapper<Tenant>()
                    .eq(Tenant::getContactPhone, tenant.getContactPhone()));
            if (p > 0) throw new BizException("联系人手机号已存在");
            tenant.setPassword(Md5Util.encrypt(tenant.getPassword()));
            tenant.setBalance(BigDecimal.ZERO);
            tenant.setPendingDeduct(BigDecimal.ZERO);
            tenantMapper.insert(tenant);
            CustomerGroup defaultGroup = new CustomerGroup();
            defaultGroup.setTenantId(tenant.getId());
            defaultGroup.setGroupName("未分组");
            customerGroupMapper.insert(defaultGroup);
        } else {
            Tenant old = tenantMapper.selectById(tenant.getId());
            if (StringUtils.hasText(tenant.getPassword())) {
                tenant.setPassword(Md5Util.encrypt(tenant.getPassword()));
            } else {
                tenant.setPassword(old.getPassword());
            }
            if (tenant.getSellPrice() != null && old.getSellPrice().compareTo(tenant.getSellPrice()) != 0) {
                PriceChangeLog log = new PriceChangeLog();
                log.setTargetType(2);
                log.setTargetId(tenant.getId());
                log.setOldPrice(old.getSellPrice());
                log.setNewPrice(tenant.getSellPrice());
                log.setOperator(UserContext.get().getUsername());
                priceChangeLogMapper.insert(log);
            }
            tenantMapper.updateById(tenant);
            if (tenant.getStatus() != null && tenant.getStatus() == 0) {
                billingService.pauseTenantTasks(tenant.getId());
            }
        }
        return Result.ok();
    }

    @PostMapping("/recharge")
    public Result<Void> recharge(@RequestBody RechargeReq req) {
        Tenant tenant = tenantMapper.selectById(req.getTenantId());
        BigDecimal newBal = tenant.getBalance().add(req.getAmount());
        tenant.setBalance(newBal);
        tenantMapper.updateById(tenant);

        RechargeOrder order = new RechargeOrder();
        order.setOrderNo(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                + ThreadLocalRandom.current().nextInt(1000, 9999));
        order.setTenantId(req.getTenantId());
        order.setRechargeAmount(req.getAmount());
        order.setArrivalBalance(newBal);
        order.setStatus(1);
        order.setAuditTime(LocalDateTime.now());
        rechargeOrderMapper.insert(order);

        BalanceLog log = new BalanceLog();
        log.setTenantId(req.getTenantId());
        log.setType(1);
        log.setAmount(req.getAmount());
        log.setBalanceAfter(newBal);
        log.setRemark("管理员充值");
        log.setRefId(order.getId());
        balanceLogMapper.insert(log);
        billingService.settlePendingDeduct(req.getTenantId());
        return Result.ok();
    }

    @PostMapping("/status/{id}")
    public Result<Void> status(@PathVariable Integer id, @RequestParam Integer status) {
        Tenant tenant = tenantMapper.selectById(id);
        tenant.setStatus(status);
        tenantMapper.updateById(tenant);
        if (status == 0) {
            billingService.pauseTenantTasks(id);
        }
        return Result.ok();
    }

    @GetMapping("/{id}/balance-log")
    public Result<PageResult<BalanceLog>> tenantBalanceLog(
            @PathVariable Integer id,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize) {
        Page<BalanceLog> p = balanceLogMapper.selectPage(new Page<>(page, pageSize),
                new LambdaQueryWrapper<BalanceLog>()
                        .eq(BalanceLog::getTenantId, id)
                        .orderByDesc(BalanceLog::getId));
        return Result.ok(PageResult.of(p.getRecords(), p.getTotal(), page, pageSize));
    }

    @GetMapping("/default-price")
    public Result<BigDecimal> defaultPrice(@RequestParam Integer priceType) {
        PriceConfig cfg = priceConfigMapper.selectOne(
                new LambdaQueryWrapper<PriceConfig>().eq(PriceConfig::getPriceType, priceType));
        return Result.ok(cfg != null ? cfg.getDefaultSellPrice() : new BigDecimal("0.1500"));
    }

    @Data
    public static class RechargeReq {
        private Integer tenantId;
        private BigDecimal amount;
    }
}
