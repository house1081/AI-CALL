package com.aicall.service;

import com.aicall.common.CallStatus;
import com.aicall.config.FreeSwitchProperties;
import com.aicall.entity.CallTask;
import com.aicall.entity.Customer;
import com.aicall.entity.CallRecord;
import com.aicall.entity.Line;
import com.aicall.mapper.CallTaskMapper;
import com.aicall.mapper.CustomerMapper;
import com.aicall.mapper.LineMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class CallTaskProgressService {

    private final CallTaskMapper callTaskMapper;
    private final LineMapper lineMapper;
    private final CustomerMapper customerMapper;
    private final FreeSwitchProperties freeSwitchProperties;

    public void onCallAnswered(Integer taskId) {
        if (taskId == null) {
            return;
        }
        CallTask task = callTaskMapper.selectById(taskId);
        if (task == null) {
            return;
        }
        task.setSuccessCount(task.getSuccessCount() + 1);
        callTaskMapper.updateById(task);
    }

    public void onCallFinished(Integer taskId, CallRecord record, int callStatus) {
        if (taskId == null) {
            return;
        }
        CallTask task = callTaskMapper.selectById(taskId);
        if (task == null) {
            return;
        }
        task.setCompletedCount(task.getCompletedCount() + 1);
        callTaskMapper.updateById(task);

        if (record.getLineId() != null) {
            Line line = lineMapper.selectById(record.getLineId());
            if (line != null) {
                line.setTodayCallCount(line.getTodayCallCount() + 1);
                lineMapper.updateById(line);
            }
        }
        if (record.getCustomerId() != null) {
            Customer customer = customerMapper.selectById(record.getCustomerId());
            if (customer != null) {
                customer.setLastCallTime(record.getCallTime());
                if (callStatus == CallStatus.CONNECTED && record.getLevel() != null) {
                    customer.setLevel(record.getLevel());
                }
                customerMapper.updateById(customer);
            }
        }
        if (freeSwitchProperties.isEnabled() && freeSwitchProperties.isTaskTerminateAfterCall()) {
            terminateRunningTaskAfterCall(taskId);
        }
    }

    /**
     * FS 外呼：单通结束后将运行中任务置为已终止(4)，任务线程不再继续拨打。
     */
    public void terminateRunningTaskAfterCall(Integer taskId) {
        if (taskId == null) {
            return;
        }
        CallTask task = callTaskMapper.selectById(taskId);
        if (task == null || task.getStatus() == null || task.getStatus() != 1) {
            return;
        }
        task.setStatus(4);
        task.setEndTime(LocalDateTime.now());
        callTaskMapper.updateById(task);
        log.info("[任务] 通话已结束，任务自动终止 taskId={}", task.getId());
    }
}
