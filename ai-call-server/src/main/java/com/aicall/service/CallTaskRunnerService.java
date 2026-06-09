package com.aicall.service;

import com.aicall.common.BizException;
import com.aicall.common.CallStatus;
import com.aicall.config.AiVoiceProperties;
import com.aicall.config.FreeSwitchProperties;
import com.aicall.entity.*;
import com.aicall.mapper.*;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@Service
@RequiredArgsConstructor
public class CallTaskRunnerService {

    private static final int CUSTOMER_PAGE_SIZE = 200;

    private final CallTaskMapper callTaskMapper;
    private final CustomerMapper customerMapper;
    private final TenantMapper tenantMapper;
    private final LineMapper lineMapper;
    private final BillingService billingService;
    private final RiskControlService riskControlService;
    private final FreeSwitchDialService freeSwitchDialService;
    private final FreeSwitchProperties freeSwitchProperties;
    private final AiVoiceProperties aiVoiceProperties;
    private final OutboundCallWaitService outboundCallWaitService;
    private final CallTaskProgressService callTaskProgressService;
    private final VoiceRuntimeSettingsService voiceRuntimeSettingsService;

    @Async
    public void runTask(Integer taskId) {
        try {
            runTaskInternal(taskId);
        } catch (Exception e) {
            log.error("外呼任务执行异常 taskId={}", taskId, e);
            CallTask t = callTaskMapper.selectById(taskId);
            if (t != null && t.getStatus() == 1) {
                t.setStatus(2);
                callTaskMapper.updateById(t);
            }
        }
    }

    private void runTaskInternal(Integer taskId) {
        CallTask task = callTaskMapper.selectById(taskId);
        if (task == null || task.getStatus() != 1) {
            return;
        }

        Tenant tenant = tenantMapper.selectById(task.getTenantId());
        List<Line> activeLines = lineMapper.selectList(
                new LambdaQueryWrapper<Line>().eq(Line::getStatus, 1));
        if (activeLines.isEmpty()) {
            log.error("外呼任务 {} 终止：无启用线路，请在总后台「线路管理」添加并启用", taskId);
            task.setStatus(2);
            callTaskMapper.updateById(task);
            return;
        }

        String mode = freeSwitchProperties.isEnabled() ? "FreeSWITCH" : "模拟外呼";
        log.info("外呼任务开始 id={} 计划客户={} 启用线路={} 模式={}",
                taskId, task.getCallCount(), activeLines.size(), mode);

        int callIntervalSec = riskControlService.config().getCallInterval();

        int pageNum = 1;
        while (true) {
            task = callTaskMapper.selectById(taskId);
            if (task == null || task.getStatus() != 1) {
                break;
            }

            Page<Customer> page = customerMapper.selectPage(
                    new Page<>(pageNum, CUSTOMER_PAGE_SIZE),
                    new LambdaQueryWrapper<Customer>()
                            .eq(Customer::getTenantId, task.getTenantId())
                            .eq(Customer::getGroupId, task.getGroupId())
                            .eq(Customer::getIsBlack, 0)
                            .orderByAsc(Customer::getId));

            if (page.getRecords().isEmpty()) {
                break;
            }

            for (Customer c : page.getRecords()) {
                task = callTaskMapper.selectById(taskId);
                if (task == null || task.getStatus() != 1) {
                    break;
                }
                if (riskControlService.isGlobalBlacklisted(c.getPhone())) {
                    continue;
                }
                if (riskControlService.isHighComplaintArea(c.getProvince())) {
                    continue;
                }
                tenant = tenantMapper.selectById(task.getTenantId());
                if (tenant.getBalance().compareTo(tenant.getSellPrice()) < 0) {
                    billingService.pauseTenantTasks(tenant.getId());
                    break;
                }
                if (riskControlService.tenantDailyLimitReached(tenant)) {
                    log.warn("商户{}已达单日呼出上限", tenant.getId());
                    task.setStatus(2);
                    callTaskMapper.updateById(task);
                    break;
                }
                if (!riskControlService.inCallWindow()) {
                    task.setStatus(2);
                    callTaskMapper.updateById(task);
                    break;
                }
                try {
                    dialWithLineRotation(task, tenant, c, activeLines);
                    if (!freeSwitchProperties.isEnabled()) {
                        riskControlService.incrementTenantDailyCallCount(tenant.getId());
                    }
                    Thread.sleep(callIntervalSec * 1000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            if (task == null || task.getStatus() != 1) {
                break;
            }
            if (page.getCurrent() >= page.getPages()) {
                break;
            }
            pageNum++;
        }

        task = callTaskMapper.selectById(taskId);
        if (task != null && task.getStatus() == 1) {
            task.setStatus(3);
            task.setEndTime(java.time.LocalDateTime.now());
            callTaskMapper.updateById(task);
            log.info("[任务] 客户列表已拨完，任务完成 taskId={}", taskId);
        }
    }

    private void dialWithLineRotation(CallTask task, Tenant tenant, Customer customer, List<Line> lines) {
        if (freeSwitchProperties.isEnabled()) {
            dialViaFreeSwitch(task, tenant, customer, lines);
            return;
        }
        List<Line> shuffled = new ArrayList<>(lines);
        Collections.shuffle(shuffled);
        for (Line line : shuffled) {
            if (riskControlService.isLineBlocked(line.getId())) {
                continue;
            }
            line = lineMapper.selectById(line.getId());
            if (riskControlService.lineDailyLimitReached(line)) {
                continue;
            }
            int status = simulateDial();
            int duration = status == CallStatus.CONNECTED
                    ? 30 + ThreadLocalRandom.current().nextInt(120) : 0;

            CallRecord record = buildRecord(task, tenant, line, customer, status, duration);
            if (status == CallStatus.CONNECTED && duration >= 60) {
                int fullMinutes = duration / 60;
                record.setPrepaidAmount(BigDecimal.ZERO);
                for (int m = 0; m < fullMinutes; m++) {
                    if (!billingService.deductRealtimeMinute(tenant.getId(), tenant.getSellPrice(),
                            customer.getPhone(), record)) {
                        record.setCallDuration(Math.min(duration, m * 60));
                        break;
                    }
                }
            }
            billingService.finishCall(record, tenant, line);
            riskControlService.afterCallRiskCheck(line, record);

            updateTaskProgress(task, status);
            updateLineStats(line);
            updateCustomer(customer, record, status);
            if (status != CallStatus.DIAL_FAIL) {
                return;
            }
        }
        saveFailedRecord(task, tenant, customer, shuffled.get(0));
    }

    private void dialViaFreeSwitch(CallTask task, Tenant tenant, Customer customer, List<Line> lines) {
        for (Line line : lines) {
            if (riskControlService.isLineBlocked(line.getId())) {
                continue;
            }
            line = lineMapper.selectById(line.getId());
            if (riskControlService.lineDailyLimitReached(line)) {
                continue;
            }
            FreeSwitchDialService.DialRequest req = new FreeSwitchDialService.DialRequest();
            req.setTenantId(tenant.getId());
            req.setLineId(line.getId());
            req.setTaskId(task.getId());
            req.setCustomerId(customer.getId());
            req.setCallee(customer.getPhone());
            int maxRings = task.getMaxRingCount() != null && task.getMaxRingCount() > 0
                    ? task.getMaxRingCount()
                    : aiVoiceProperties.getAnswerMaxRingCount();
            req.setMaxRingCount(maxRings);
            voiceRuntimeSettingsService.logEffectiveVoiceProfile(
                    "任务" + task.getId() + " phone=" + customer.getPhone());
            FreeSwitchDialService.DialResult result = freeSwitchDialService.originate(req);
            if (result.isSuccess()) {
                log.info("FS 外呼已下发 phone={} uuid={} reply={}",
                        customer.getPhone(), result.getFsUuid(), result.getEslReply());
                riskControlService.incrementTenantDailyCallCount(tenant.getId());
                int waitSec = Math.max(90, aiVoiceProperties.getDialogMaxCallSec() + 60);
                outboundCallWaitService.awaitFinished(task.getId(), result.getFsUuid(), waitSec);
                if (freeSwitchProperties.isTaskTerminateAfterCall()) {
                    callTaskProgressService.terminateRunningTaskAfterCall(task.getId());
                }
                return;
            }
            log.warn("FS 外呼失败 phone={} lineId={} mode={} hint={}",
                    customer.getPhone(), line.getId(), result.getMode(), result.getHint());
        }
        saveFailedRecord(task, tenant, customer, lines.get(0));
    }

    private int simulateDial() {
        double r = Math.random();
        if (r < 0.55) return CallStatus.CONNECTED;
        if (r < 0.70) return CallStatus.NO_ANSWER;
        if (r < 0.80) return CallStatus.EMPTY;
        if (r < 0.88) return CallStatus.POWER_OFF;
        if (r < 0.95) return CallStatus.REJECT;
        return CallStatus.DIAL_FAIL;
    }

    private CallRecord buildRecord(CallTask task, Tenant tenant, Line line,
                                   Customer customer, int status, int duration) {
        CallRecord record = new CallRecord();
        record.setTenantId(tenant.getId());
        record.setLineId(line.getId());
        record.setCustomerId(customer.getId());
        record.setCustomerPhone(customer.getPhone());
        record.setCallDuration(duration);
        record.setCallStatus(status);
        record.setTaskId(task.getId());
        record.setLevel("D");
        record.setCallTime(java.time.LocalDateTime.now());
        record.setRecordUrl(status == CallStatus.CONNECTED ? "/uploads/record/demo.wav" : "");
        if (status == CallStatus.CONNECTED) {
            record.setDialogText("模拟对话：已介绍业务并询问需求");
            record.setCustomerNeed("有了解意向");
            record.setCustomerPain("价格偏高");
            record.setBudget("10万左右");
            record.setNextTime("下周联系");
            record.setLevel("B");
        }
        return record;
    }

    private void saveFailedRecord(CallTask task, Tenant tenant, Customer customer, Line line) {
        CallRecord record = buildRecord(task, tenant, line, customer, CallStatus.DIAL_FAIL, 0);
        billingService.finishCall(record, tenant, line);
        updateTaskProgress(task, CallStatus.DIAL_FAIL);
    }

    private void updateTaskProgress(CallTask task, int status) {
        task.setCompletedCount(task.getCompletedCount() + 1);
        if (status == CallStatus.CONNECTED) {
            task.setSuccessCount(task.getSuccessCount() + 1);
        }
        callTaskMapper.updateById(task);
    }

    private void updateLineStats(Line line) {
        line.setTodayCallCount(line.getTodayCallCount() + 1);
        line.setCurrentConcurrent(Math.min(line.getCurrentConcurrent() + 1, 50));
        lineMapper.updateById(line);
        line.setCurrentConcurrent(Math.max(0, line.getCurrentConcurrent() - 1));
        lineMapper.updateById(line);
    }

    private void updateCustomer(Customer customer, CallRecord record, int status) {
        customer.setLastCallTime(java.time.LocalDateTime.now());
        if (status == CallStatus.CONNECTED && record.getLevel() != null) {
            customer.setLevel(record.getLevel());
        }
        customerMapper.updateById(customer);
    }

    public void validateStart(CallTask task, Tenant tenant) {
        if (task.getStatus() != null && task.getStatus() == 4) {
            throw new BizException("任务已终止，无法再次启动");
        }
        billingService.assertCanStartTask(tenant);
        if (!riskControlService.inCallWindow()) {
            var risk = riskControlService.config();
            throw new BizException("当前不在外呼时间段 " + risk.getCallStartTime() + "-" + risk.getCallEndTime()
                    + "，可在总后台「风控配置」调整");
        }
        if (riskControlService.tenantDailyLimitReached(tenant)) {
            throw new BizException("已达单日最大呼出次数限制");
        }
        long lineCount = lineMapper.selectCount(new LambdaQueryWrapper<Line>().eq(Line::getStatus, 1));
        if (lineCount == 0) {
            throw new BizException("无可用线路，请先在总后台「线路管理」添加并启用至少 1 条线路");
        }
        if (task.getCallCount() == null || task.getCallCount() <= 0) {
            throw new BizException("所选客户分组无客户，请先在「客户管理」导入客户");
        }
        voiceRuntimeSettingsService.validateVoiceReady(task.getId());
    }

    public String dialModeHint() {
        return freeSwitchProperties.isEnabled()
                ? "真实外呼（ESL " + freeSwitchProperties.eslEndpoint() + "），接通/挂断由 FS 回调"
                : "模拟外呼（freeswitch.enabled=false），将自动生成通话记录";
    }
}
