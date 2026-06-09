package com.aicall.controller.tenant;

import com.aicall.common.BizException;
import com.aicall.common.CallTaskDialMode;
import com.aicall.common.CallTaskDialRules;
import com.aicall.common.SilenceProfile;
import com.aicall.common.PageResult;
import com.aicall.common.Result;
import com.aicall.context.UserContext;
import com.aicall.entity.*;
import com.aicall.mapper.*;
import com.aicall.dto.CallRecordVO;
import com.aicall.dto.CallTaskVO;
import com.aicall.dto.IntentCustomerVO;
import com.aicall.service.CallTaskRunnerService;
import com.aicall.service.TaskCallHangupService;
import com.aicall.service.NoticeService;
import com.aicall.service.PortalQueryService;
import com.aicall.service.StatisticsService;
import com.aicall.service.VoiceRuntimeSettingsService;
import com.aicall.entity.SystemNotice;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/tenant/portal")
@RequiredArgsConstructor
public class TenantPortalController {

    private static final Pattern PHONE = Pattern.compile("^1\\d{10}$");

    private final TenantMapper tenantMapper;
    private final CustomerMapper customerMapper;
    private final CustomerGroupMapper customerGroupMapper;
    private final CallTaskMapper callTaskMapper;
    private final CallRecordMapper callRecordMapper;
    private final RechargeOrderMapper rechargeOrderMapper;
    private final BalanceLogMapper balanceLogMapper;
    private final StatisticsService statisticsService;
    private final CallTaskRunnerService callTaskRunnerService;
    private final TaskCallHangupService taskCallHangupService;
    private final NoticeService noticeService;
    private final PortalQueryService portalQueryService;
    private final VoiceRuntimeSettingsService voiceRuntimeSettingsService;

    private int tenantId() {
        return UserContext.get().getTenantId();
    }

    @GetMapping("/home")
    public Result<Map<String, Object>> home() {
        Tenant t = tenantMapper.selectById(tenantId());
        LocalDate today = LocalDate.now();
        LocalDate monthStart = today.withDayOfMonth(1);
        Map<String, Object> todayStats = statisticsService.dashboard(today, today, tenantId(), null);
        Map<String, Object> monthStats = statisticsService.dashboard(monthStart, today, tenantId(), null);
        Map<String, Object> m = new HashMap<>(todayStats);
        m.put("balance", t.getBalance());
        m.put("sellPrice", t.getSellPrice());
        m.put("priceType", t.getPriceType());
        m.put("priceTypeName", switch (t.getPriceType() != null ? t.getPriceType() : 1) {
            case 2 -> "企业价";
            case 3 -> "代理价";
            default -> "零售价";
        });
        m.put("pendingDeduct", t.getPendingDeduct() != null ? t.getPendingDeduct() : BigDecimal.ZERO);
        m.put("monthDeduct", monthStats.get("totalDeduct"));
        m.put("monthCalls", monthStats.get("totalCalls"));
        int minutes = t.getSellPrice().compareTo(BigDecimal.ZERO) > 0
                ? t.getBalance().divide(t.getSellPrice(), 0, RoundingMode.DOWN).intValue() : 0;
        m.put("availableMinutes", minutes);
        return Result.ok(m);
    }

    @GetMapping("/notice/list")
    public Result<List<SystemNotice>> notices() {
        return Result.ok(noticeService.listForTenant(tenantId()));
    }

    @PostMapping("/notice/read/{id}")
    public Result<Void> readNotice(@PathVariable Integer id) {
        noticeService.markRead(id);
        return Result.ok();
    }

    @GetMapping("/profile")
    public Result<Tenant> profile() {
        Tenant t = tenantMapper.selectById(tenantId());
        t.setPassword(null);
        return Result.ok(t);
    }

    // --- 客户分组 ---
    @GetMapping("/group/list")
    public Result<List<CustomerGroup>> groups() {
        return Result.ok(customerGroupMapper.selectList(
                new LambdaQueryWrapper<CustomerGroup>().eq(CustomerGroup::getTenantId, tenantId())));
    }

    @PostMapping("/group/save")
    public Result<Void> groupSave(@RequestBody CustomerGroup g) {
        g.setTenantId(tenantId());
        if (g.getId() == null) {
            customerGroupMapper.insert(g);
        } else {
            customerGroupMapper.updateById(g);
        }
        return Result.ok();
    }

    @DeleteMapping("/group/{id}")
    public Result<Void> groupDel(@PathVariable Integer id) {
        customerGroupMapper.deleteById(id);
        customerMapper.selectList(new LambdaQueryWrapper<Customer>()
                .eq(Customer::getGroupId, id).eq(Customer::getTenantId, tenantId()))
                .forEach(c -> {
                    c.setGroupId(1);
                    c.setGroupName("未分组");
                    customerMapper.updateById(c);
                });
        return Result.ok();
    }

    // --- 客户 ---
    @GetMapping("/customer/list")
    public Result<PageResult<Customer>> customers(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) String phone,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) Integer groupId,
            @RequestParam(required = false) String level,
            @RequestParam(required = false) Integer isBlack) {
        LambdaQueryWrapper<Customer> q = new LambdaQueryWrapper<Customer>()
                .eq(Customer::getTenantId, tenantId());
        if (StringUtils.hasText(phone)) {
            q.like(Customer::getPhone, phone);
        }
        if (StringUtils.hasText(name)) {
            q.like(Customer::getName, name);
        }
        if (groupId != null) {
            q.eq(Customer::getGroupId, groupId);
        }
        if (StringUtils.hasText(level)) {
            q.eq(Customer::getLevel, level);
        }
        if (isBlack != null) {
            q.eq(Customer::getIsBlack, isBlack);
        }
        q.orderByDesc(Customer::getId);
        Page<Customer> p = customerMapper.selectPage(new Page<>(page, pageSize), q);
        return Result.ok(PageResult.of(p.getRecords(), p.getTotal(), page, pageSize));
    }

    @PostMapping("/customer/save")
    public Result<Void> customerSave(@RequestBody Customer c) {
        c.setTenantId(tenantId());
        if (!PHONE.matcher(c.getPhone()).matches()) {
            throw new BizException("手机号格式错误");
        }
        if (c.getId() == null) {
            long cnt = customerMapper.selectCount(new LambdaQueryWrapper<Customer>()
                    .eq(Customer::getPhone, c.getPhone()).eq(Customer::getTenantId, tenantId()));
            if (cnt > 0) {
                throw new BizException("手机号已存在");
            }
            customerMapper.insert(c);
        } else {
            Customer existing = customerMapper.selectById(c.getId());
            if (existing == null || !existing.getTenantId().equals(tenantId())) {
                throw new BizException("客户不存在");
            }
            long cnt = customerMapper.selectCount(new LambdaQueryWrapper<Customer>()
                    .eq(Customer::getPhone, c.getPhone())
                    .eq(Customer::getTenantId, tenantId())
                    .ne(Customer::getId, c.getId()));
            if (cnt > 0) {
                throw new BizException("手机号已存在");
            }
            if (c.getGroupId() != null) {
                CustomerGroup g = customerGroupMapper.selectById(c.getGroupId());
                if (g != null) {
                    c.setGroupName(g.getGroupName());
                }
            }
            customerMapper.updateById(c);
        }
        return Result.ok();
    }

    @PostMapping("/customer/import")
    public Result<Map<String, Integer>> customerImport(@RequestBody List<Customer> rows) {
        int ok = 0, fail = 0;
        for (Customer c : rows) {
            if (!PHONE.matcher(c.getPhone()).matches()) {
                fail++;
                continue;
            }
            long cnt = customerMapper.selectCount(new LambdaQueryWrapper<Customer>()
                    .eq(Customer::getPhone, c.getPhone()).eq(Customer::getTenantId, tenantId()));
            if (cnt > 0) {
                fail++;
                continue;
            }
            c.setTenantId(tenantId());
            if (c.getGroupId() == null) {
                c.setGroupId(1);
            }
            if (!StringUtils.hasText(c.getGroupName())) {
                c.setGroupName("未分组");
            }
            c.setIsBlack(0);
            customerMapper.insert(c);
            ok++;
        }
        return Result.ok(Map.of("success", ok, "fail", fail));
    }

    @PostMapping("/customer/black")
    public Result<Void> black(@RequestBody IdsReq req, @RequestParam Integer isBlack) {
        for (Integer id : req.getIds()) {
            Customer c = customerMapper.selectById(id);
            if (c != null && c.getTenantId().equals(tenantId())) {
                c.setIsBlack(isBlack);
                customerMapper.updateById(c);
            }
        }
        return Result.ok();
    }

    @PostMapping("/customer/move-group")
    public Result<Void> moveGroup(@RequestBody MoveGroupReq req) {
        CustomerGroup g = customerGroupMapper.selectById(req.getGroupId());
        for (Integer id : req.getIds()) {
            Customer c = customerMapper.selectById(id);
            if (c != null && c.getTenantId().equals(tenantId())) {
                c.setGroupId(req.getGroupId());
                c.setGroupName(g != null ? g.getGroupName() : "未分组");
                customerMapper.updateById(c);
            }
        }
        return Result.ok();
    }

    // --- 外呼任务 ---
    @GetMapping("/task/list")
    public Result<PageResult<CallTaskVO>> tasks(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize) {
        Page<CallTask> p = callTaskMapper.selectPage(new Page<>(page, pageSize),
                new LambdaQueryWrapper<CallTask>()
                        .eq(CallTask::getTenantId, tenantId())
                        .orderByDesc(CallTask::getId));
        List<CallTaskVO> vos = portalQueryService.enrichTasks(p.getRecords());
        return Result.ok(PageResult.of(vos, p.getTotal(), page, pageSize));
    }

    /** 任务详情（勿用 GET /task/{id}，与 DELETE 同路径易在旧版本冲突） */
    @GetMapping("/task/detail/{id}")
    public Result<CallTaskVO> taskDetail(@PathVariable Integer id) {
        CallTask task = requireTask(id);
        List<CallTaskVO> vos = portalQueryService.enrichTasks(List.of(task));
        return Result.ok(vos.isEmpty() ? new CallTaskVO() : vos.get(0));
    }

    @GetMapping("/task/{id}/records")
    public Result<PageResult<CallRecordVO>> taskRecords(
            @PathVariable Integer id,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize) {
        CallTask task = callTaskMapper.selectById(id);
        if (task == null || !task.getTenantId().equals(tenantId())) {
            throw new BizException("任务不存在");
        }
        Page<CallRecord> p = callRecordMapper.selectPage(new Page<>(page, pageSize),
                new LambdaQueryWrapper<CallRecord>()
                        .eq(CallRecord::getTaskId, id)
                        .orderByDesc(CallRecord::getId));
        List<CallRecordVO> vos = portalQueryService.enrichCallRecords(p.getRecords());
        vos.forEach(r -> { r.setCostAmount(null); r.setProfit(null); });
        return Result.ok(PageResult.of(vos, p.getTotal(), page, pageSize));
    }

    @PostMapping("/task/save")
    public Result<Void> taskSave(@RequestBody CallTask task) {
        task.setTenantId(tenantId());
        normalizeCallTask(task);
        if (task.getId() == null) {
            long count = customerMapper.selectCount(new LambdaQueryWrapper<Customer>()
                    .eq(Customer::getTenantId, tenantId())
                    .eq(Customer::getGroupId, task.getGroupId())
                    .eq(Customer::getIsBlack, 0));
            task.setCallCount((int) count);
            task.setCompletedCount(0);
            task.setSuccessCount(0);
            task.setStatus(0);
            callTaskMapper.insert(task);
            tryAutoStartAfterSave(task);
        } else {
            CallTask existing = requireTask(task.getId());
            applyScheduledTaskPendingStatus(task, existing);
            callTaskMapper.updateById(task);
            tryAutoStartAfterSave(task);
        }
        return Result.ok();
    }

    private void normalizeCallTask(CallTask task) {
        if (task.getDialMode() == null) {
            task.setDialMode(CallTaskDialMode.IMMEDIATE);
        }
        if (task.getMaxRingCount() == null || task.getMaxRingCount() < 3) {
            task.setMaxRingCount(10);
        }
        if (!StringUtils.hasText(task.getTaskRules())) {
            task.setTaskRules(CallTaskDialRules.DEFAULT_TEXT);
        }
        if (StringUtils.hasText(task.getSilenceProfile())) {
            String profile = task.getSilenceProfile().trim();
            if (!SilenceProfile.isValid(profile)) {
                task.setSilenceProfile(null);
            } else {
                task.setSilenceProfile(SilenceProfile.normalize(profile));
            }
        } else {
            task.setSilenceProfile(null);
        }
        if (task.getAutoAddWechat() == null) {
            task.setAutoAddWechat(0);
        }
        if (task.getAutoAddWechat() != null && task.getAutoAddWechat() == 1) {
            if (!StringUtils.hasText(task.getWechatAddApiUrl())) {
                throw new BizException("开启自动加V时请填写加V接口地址");
            }
            if (!StringUtils.hasText(task.getWechatAddMessage())) {
                task.setWechatAddMessage("你好，我是刚才和您通话的顾问，方便通过一下吗？");
            }
        } else {
            task.setAutoAddWechat(0);
        }
        if (task.getDialMode() == CallTaskDialMode.SCHEDULED && task.getScheduledStartTime() == null) {
            throw new BizException("定时外呼请设置开始时间");
        }
        if (task.getPromptId() == null) {
            Tenant tenant = tenantMapper.selectById(task.getTenantId());
            if (tenant != null && tenant.getPromptId() != null) {
                task.setPromptId(tenant.getPromptId());
            }
        }
    }

    /** 非运行中的定时任务保存后保持「未启动」，供 CallTaskScheduleService 到点扫描 */
    private void applyScheduledTaskPendingStatus(CallTask task, CallTask existing) {
        if (task.getDialMode() == null || task.getDialMode() != CallTaskDialMode.SCHEDULED) {
            return;
        }
        if (existing.getStatus() != null && existing.getStatus() == 1) {
            return;
        }
        task.setStatus(0);
    }

    private void tryAutoStartAfterSave(CallTask task) {
        if (!Boolean.TRUE.equals(task.getAutoStart())) {
            return;
        }
        if (task.getDialMode() == CallTaskDialMode.SCHEDULED
                && task.getScheduledStartTime() != null
                && task.getScheduledStartTime().isAfter(LocalDateTime.now())) {
            return;
        }
        Tenant tenant = tenantMapper.selectById(task.getTenantId());
        callTaskRunnerService.validateStart(task, tenant);
        task.setStatus(1);
        task.setStartTime(LocalDateTime.now());
        callTaskMapper.updateById(task);
        callTaskRunnerService.runTask(task.getId());
    }

    @PostMapping("/task/{id}/start")
    public Result<Map<String, Object>> taskStart(@PathVariable Integer id) {
        CallTask task = callTaskMapper.selectById(id);
        if (!task.getTenantId().equals(tenantId())) {
            throw new BizException("无权操作");
        }
        Tenant tenant = tenantMapper.selectById(tenantId());
        callTaskRunnerService.validateStart(task, tenant);
        task.setStatus(1);
        task.setStartTime(LocalDateTime.now());
        callTaskMapper.updateById(task);
        callTaskRunnerService.runTask(id);
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("taskId", id);
        data.put("callCount", task.getCallCount());
        data.put("hint", callTaskRunnerService.dialModeHint());
        return Result.ok(data);
    }

    @PostMapping("/task/{id}/pause")
    public Result<Map<String, Object>> taskPause(@PathVariable Integer id) {
        CallTask task = requireTask(id);
        if (task.getStatus() != null && task.getStatus() == 1) {
            int hung = taskCallHangupService.hangupAllForTask(id, true);
            task.setStatus(2);
            callTaskMapper.updateById(task);
            return Result.ok(Map.of("taskId", id, "hungupChannels", hung));
        }
        task.setStatus(2);
        callTaskMapper.updateById(task);
        return Result.ok(Map.of("taskId", id, "hungupChannels", 0));
    }

    @PostMapping("/task/{id}/stop")
    public Result<Map<String, Object>> taskStop(@PathVariable Integer id) {
        CallTask task = requireTask(id);
        int hung = taskCallHangupService.hangupAllForTask(id, false);
        task.setStatus(4);
        task.setEndTime(LocalDateTime.now());
        callTaskMapper.updateById(task);
        return Result.ok(Map.of("taskId", id, "hungupChannels", hung));
    }

    private CallTask requireTask(Integer id) {
        CallTask task = callTaskMapper.selectById(id);
        if (task == null || !task.getTenantId().equals(tenantId())) {
            throw new BizException("任务不存在");
        }
        return task;
    }

    @DeleteMapping("/task/{id}")
    public Result<Void> taskDelete(@PathVariable Integer id) {
        CallTask task = callTaskMapper.selectById(id);
        if (task == null || !task.getTenantId().equals(tenantId())) {
            throw new BizException("任务不存在");
        }
        if (task.getStatus() != null && task.getStatus() == 1) {
            throw new BizException("运行中的任务不能删除，请先暂停或终止");
        }
        callTaskMapper.deleteById(id);
        return Result.ok();
    }

    // --- 通话记录 ---
    @GetMapping("/call-record/list")
    public Result<PageResult<CallRecordVO>> callRecords(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) String phone,
            @RequestParam(required = false) Integer callStatus,
            @RequestParam(required = false) String level,
            @RequestParam(required = false) Integer taskId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        LambdaQueryWrapper<CallRecord> q = new LambdaQueryWrapper<CallRecord>()
                .eq(CallRecord::getTenantId, tenantId());
        if (StringUtils.hasText(phone)) {
            q.like(CallRecord::getCustomerPhone, phone);
        }
        if (callStatus != null) {
            q.eq(CallRecord::getCallStatus, callStatus);
        }
        if (StringUtils.hasText(level)) {
            q.eq(CallRecord::getLevel, level);
        }
        if (taskId != null) {
            q.eq(CallRecord::getTaskId, taskId);
        }
        if (startDate != null) {
            q.ge(CallRecord::getCallTime, startDate.atStartOfDay());
        }
        if (endDate != null) {
            q.lt(CallRecord::getCallTime, endDate.plusDays(1).atStartOfDay());
        }
        q.orderByDesc(CallRecord::getId);
        Page<CallRecord> p = callRecordMapper.selectPage(new Page<>(page, pageSize), q);
        List<CallRecordVO> vos = portalQueryService.enrichCallRecords(p.getRecords());
        vos.forEach(r -> { r.setCostAmount(null); r.setProfit(null); });
        return Result.ok(PageResult.of(vos, p.getTotal(), page, pageSize));
    }

    @GetMapping("/customer/{id}/records")
    public Result<List<CallRecordVO>> customerRecords(@PathVariable Integer id) {
        Customer c = customerMapper.selectById(id);
        if (c == null || !c.getTenantId().equals(tenantId())) {
            throw new BizException("客户不存在");
        }
        List<CallRecord> list = callRecordMapper.selectList(new LambdaQueryWrapper<CallRecord>()
                .eq(CallRecord::getTenantId, tenantId())
                .eq(CallRecord::getCustomerPhone, c.getPhone())
                .orderByDesc(CallRecord::getId)
                .last("LIMIT 50"));
        List<CallRecordVO> vos = portalQueryService.enrichCallRecords(list);
        vos.forEach(r -> { r.setCostAmount(null); r.setProfit(null); });
        return Result.ok(vos);
    }

    @GetMapping("/intent/list")
    public Result<PageResult<IntentCustomerVO>> intentList(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) String level) {
        long total = portalQueryService.countIntentCustomers(tenantId(), level);
        List<IntentCustomerVO> list = portalQueryService.listIntentCustomers(tenantId(), level, page, pageSize);
        return Result.ok(PageResult.of(list, total, page, pageSize));
    }

    // --- 充值 ---
    @PostMapping("/recharge/submit")
    public Result<Void> rechargeSubmit(@RequestBody RechargeReq req) {
        if (req.getAmount().compareTo(new BigDecimal("100")) < 0) {
            throw new BizException("最小充值金额100元");
        }
        RechargeOrder order = new RechargeOrder();
        order.setOrderNo(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                + ThreadLocalRandom.current().nextInt(1000, 9999));
        order.setTenantId(tenantId());
        order.setRechargeAmount(req.getAmount());
        order.setStatus(0);
        order.setVoucherUrl(req.getVoucherUrl());
        rechargeOrderMapper.insert(order);
        return Result.ok();
    }

    @GetMapping("/recharge/list")
    public Result<PageResult<RechargeOrder>> rechargeList(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize) {
        Page<RechargeOrder> p = rechargeOrderMapper.selectPage(new Page<>(page, pageSize),
                new LambdaQueryWrapper<RechargeOrder>()
                        .eq(RechargeOrder::getTenantId, tenantId())
                        .orderByDesc(RechargeOrder::getId));
        return Result.ok(PageResult.of(p.getRecords(), p.getTotal(), page, pageSize));
    }

    @GetMapping("/balance-log/list")
    public Result<PageResult<BalanceLog>> balanceLogs(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) Integer type,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        LambdaQueryWrapper<BalanceLog> q = new LambdaQueryWrapper<BalanceLog>()
                .eq(BalanceLog::getTenantId, tenantId());
        if (type != null) {
            q.eq(BalanceLog::getType, type);
        }
        if (startDate != null) {
            q.ge(BalanceLog::getCreateTime, startDate.atStartOfDay());
        }
        if (endDate != null) {
            q.lt(BalanceLog::getCreateTime, endDate.plusDays(1).atStartOfDay());
        }
        q.orderByDesc(BalanceLog::getId);
        Page<BalanceLog> p = balanceLogMapper.selectPage(new Page<>(page, pageSize), q);
        return Result.ok(PageResult.of(p.getRecords(), p.getTotal(), page, pageSize));
    }

    @Data
    public static class IdsReq {
        private List<Integer> ids;
    }

    @Data
    public static class MoveGroupReq {
        private List<Integer> ids;
        private Integer groupId;
    }

    @Data
    public static class RechargeReq {
        private BigDecimal amount;
        private String voucherUrl;
    }
}
