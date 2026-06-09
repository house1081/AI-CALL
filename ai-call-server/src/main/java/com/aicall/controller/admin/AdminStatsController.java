package com.aicall.controller.admin;

import com.aicall.common.PageResult;
import com.aicall.common.Result;
import com.aicall.entity.CallRecord;
import com.aicall.entity.RechargeOrder;
import com.aicall.entity.SystemNotice;
import com.aicall.mapper.CallRecordMapper;
import com.aicall.mapper.RechargeOrderMapper;
import com.aicall.service.NoticeService;
import com.aicall.service.ProfitMonitorService;
import com.aicall.service.RiskControlService;
import com.aicall.service.StatisticsService;
import com.aicall.entity.StatisticsMonthly;
import com.aicall.mapper.BalanceLogMapper;
import com.aicall.entity.BalanceLog;
import com.aicall.mapper.TenantMapper;
import com.aicall.entity.Tenant;
import com.aicall.entity.RiskLog;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminStatsController {

    private final StatisticsService statisticsService;
    private final CallRecordMapper callRecordMapper;
    private final RechargeOrderMapper rechargeOrderMapper;
    private final NoticeService noticeService;
    private final RiskControlService riskControlService;
    private final ProfitMonitorService profitMonitorService;
    private final BalanceLogMapper balanceLogMapper;
    private final TenantMapper tenantMapper;

    @GetMapping("/dashboard")
    public Result<Map<String, Object>> dashboard(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end) {
        if (start == null) {
            start = LocalDate.now();
        }
        if (end == null) {
            end = LocalDate.now();
        }
        return Result.ok(statisticsService.dashboard(start, end, null, null));
    }

    @GetMapping("/statistics/forced-hangup")
    public Result<Map<String, Object>> forcedHangupStats(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end,
            @RequestParam(required = false) Integer tenantId) {
        return Result.ok(statisticsService.forcedHangupStats(start, end, tenantId));
    }

    @GetMapping("/dashboard/trend")
    public Result<List<Map<String, Object>>> trend(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end,
            @RequestParam(required = false) Integer tenantId) {
        return Result.ok(statisticsService.dailyTrend(start, end, tenantId));
    }

    /** PRD 2.4 盈利异常监控 */
    @GetMapping("/profit/monitor")
    public Result<Map<String, Object>> profitMonitor() {
        return Result.ok(profitMonitorService.monitor());
    }

    /** PRD 2.3 利润统计：支持商户/线路/时间范围 */
    @GetMapping("/statistics/profit")
    public Result<Map<String, Object>> profitStats(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end,
            @RequestParam(required = false) Integer tenantId,
            @RequestParam(required = false) Integer lineId) {
        return Result.ok(statisticsService.profitReport(start, end, tenantId, lineId));
    }

    @GetMapping("/statistics/monthly")
    public Result<List<StatisticsMonthly>> monthlyStats(
            @RequestParam(required = false) String month,
            @RequestParam(required = false) Integer tenantId) {
        return Result.ok(statisticsService.monthlyList(month, tenantId));
    }

    /** PRD 2.1 规则4：账单与余额变动对账 */
    @GetMapping("/tenant/{tenantId}/reconcile")
    public Result<Map<String, Object>> reconcile(@PathVariable Integer tenantId) {
        Tenant tenant = tenantMapper.selectById(tenantId);
        java.math.BigDecimal logSum = balanceLogMapper.selectList(
                new LambdaQueryWrapper<BalanceLog>().eq(BalanceLog::getTenantId, tenantId))
                .stream()
                .map(BalanceLog::getAmount)
                .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
        Map<String, Object> m = new java.util.HashMap<>();
        m.put("currentBalance", tenant.getBalance());
        m.put("pendingDeduct", tenant.getPendingDeduct());
        m.put("balanceLogNet", logSum);
        m.put("sellPrice", tenant.getSellPrice());
        return Result.ok(m);
    }

    @GetMapping("/profit/abnormal")
    public Result<List<CallRecord>> abnormal(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end) {
        if (start == null) start = LocalDate.now().minusDays(30);
        if (end == null) end = LocalDate.now();
        return Result.ok(statisticsService.abnormalProfitRecords(start, end));
    }

    @GetMapping("/notice/list")
    public Result<List<SystemNotice>> notices(@RequestParam(defaultValue = "false") boolean unreadOnly) {
        return Result.ok(noticeService.listForAdmin(unreadOnly));
    }

    @PostMapping("/notice/read/{id}")
    public Result<Void> readNotice(@PathVariable Integer id) {
        noticeService.markRead(id);
        return Result.ok();
    }

    @GetMapping("/risk/log")
    public Result<List<RiskLog>> riskLogs() {
        return Result.ok(riskControlService.recentLogs(50));
    }

    @GetMapping("/call-record/list")
    public Result<PageResult<CallRecord>> callRecords(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) Integer tenantId,
            @RequestParam(required = false) Integer callStatus,
            @RequestParam(required = false) String phone,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime) {
        LambdaQueryWrapper<CallRecord> q = new LambdaQueryWrapper<>();
        if (tenantId != null) {
            q.eq(CallRecord::getTenantId, tenantId);
        }
        if (callStatus != null) {
            q.eq(CallRecord::getCallStatus, callStatus);
        }
        if (StringUtils.hasText(phone)) {
            q.like(CallRecord::getCustomerPhone, phone);
        }
        if (startTime != null) {
            q.ge(CallRecord::getCallTime, startTime);
        }
        if (endTime != null) {
            q.le(CallRecord::getCallTime, endTime);
        }
        q.orderByDesc(CallRecord::getId);
        Page<CallRecord> p = callRecordMapper.selectPage(new Page<>(page, pageSize), q);
        return Result.ok(PageResult.of(p.getRecords(), p.getTotal(), page, pageSize));
    }

    @GetMapping("/recharge/list")
    public Result<PageResult<RechargeOrder>> rechargeList(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) Integer status) {
        LambdaQueryWrapper<RechargeOrder> q = new LambdaQueryWrapper<>();
        if (status != null) {
            q.eq(RechargeOrder::getStatus, status);
        }
        q.orderByDesc(RechargeOrder::getId);
        Page<RechargeOrder> p = rechargeOrderMapper.selectPage(new Page<>(page, pageSize), q);
        return Result.ok(PageResult.of(p.getRecords(), p.getTotal(), page, pageSize));
    }

}
