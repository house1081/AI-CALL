package com.aicall.schedule;

import com.aicall.common.CallTaskDialMode;
import com.aicall.entity.CallTask;
import com.aicall.entity.Tenant;
import com.aicall.mapper.CallTaskMapper;
import com.aicall.mapper.TenantMapper;
import com.aicall.service.CallTaskRunnerService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 定时外呼：到达 scheduled_start_time 后自动启动 status=0 的任务。
 * 每 10 秒扫描一次；服务重启后会补扫已到点但未启动的任务。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CallTaskScheduleService {

    private final CallTaskMapper callTaskMapper;
    private final TenantMapper tenantMapper;
    private final CallTaskRunnerService callTaskRunnerService;

    @Scheduled(fixedDelay = 10_000, initialDelay = 5_000)
    public void launchDueScheduledTasks() {
        LocalDateTime now = LocalDateTime.now();
        List<CallTask> due = callTaskMapper.selectList(
                new LambdaQueryWrapper<CallTask>()
                        .eq(CallTask::getStatus, 0)
                        .eq(CallTask::getDialMode, CallTaskDialMode.SCHEDULED)
                        .isNotNull(CallTask::getScheduledStartTime)
                        .le(CallTask::getScheduledStartTime, now));
        if (due.isEmpty()) {
            return;
        }
        log.info("[定时外呼] 扫描到 {} 个到点任务 now={}", due.size(), now);
        for (CallTask task : due) {
            try {
                Tenant tenant = tenantMapper.selectById(task.getTenantId());
                if (tenant == null) {
                    log.warn("[定时外呼] 商户不存在 taskId={} tenantId={}", task.getId(), task.getTenantId());
                    continue;
                }
                callTaskRunnerService.validateStart(task, tenant);
                task.setStatus(1);
                task.setStartTime(now);
                callTaskMapper.updateById(task);
                log.info("[定时外呼] 到点自动启动 taskId={} name={} scheduled={}",
                        task.getId(), task.getTaskName(), task.getScheduledStartTime());
                callTaskRunnerService.runTask(task.getId());
            } catch (Exception e) {
                log.warn("[定时外呼] 启动失败 taskId={} scheduled={} reason={}",
                        task.getId(), task.getScheduledStartTime(), e.getMessage());
            }
        }
    }
}
