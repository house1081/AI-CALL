package com.aicall.service;

import com.aicall.config.FreeSwitchProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * FS 外呼：originate 后阻塞等待通话结束，避免连续多发、并在结束后更新任务状态。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OutboundCallWaitService {

    private final FreeSwitchProperties freeSwitchProperties;
    private final CallTaskProgressService callTaskProgressService;

    private final Map<String, CompletableFuture<Void>> byUuid = new ConcurrentHashMap<>();
    private final Map<Integer, CompletableFuture<Void>> byTaskId = new ConcurrentHashMap<>();

    public void register(String fsUuid, Integer taskId) {
        if (!StringUtils.hasText(fsUuid)) {
            return;
        }
        CompletableFuture<Void> future = new CompletableFuture<>();
        byUuid.put(fsUuid, future);
        if (taskId != null) {
            byTaskId.put(taskId, future);
        }
    }

    public void complete(String fsUuid, Integer taskId) {
        if (StringUtils.hasText(fsUuid)) {
            CompletableFuture<Void> f = byUuid.remove(fsUuid);
            if (f != null) {
                f.complete(null);
            }
        }
        if (taskId != null) {
            CompletableFuture<Void> f = byTaskId.remove(taskId);
            if (f != null) {
                f.complete(null);
            }
        }
    }

    /**
     * @return true 在时限内结束；false 超时（避免任务线程永久阻塞）
     */
    public boolean awaitFinished(Integer taskId, String fsUuid, int timeoutSec) {
        CompletableFuture<Void> future = null;
        if (taskId != null) {
            future = byTaskId.get(taskId);
        }
        if (future == null && StringUtils.hasText(fsUuid)) {
            future = byUuid.get(fsUuid);
        }
        if (future == null) {
            return true;
        }
        try {
            future.get(Math.max(30, timeoutSec), TimeUnit.SECONDS);
            log.info("[外呼等待] 通话已结束 taskId={} uuid={}", taskId, fsUuid);
            return true;
        } catch (TimeoutException e) {
            log.warn("[外呼等待] 超时 {}s taskId={} uuid={}", timeoutSec, taskId, fsUuid);
            byUuid.remove(fsUuid);
            if (taskId != null) {
                byTaskId.remove(taskId);
            }
            if (taskId != null && freeSwitchProperties.isTaskTerminateAfterCall()) {
                callTaskProgressService.terminateRunningTaskAfterCall(taskId);
            }
            return false;
        } catch (Exception e) {
            log.warn("[外呼等待] 异常 taskId={} uuid={}: {}", taskId, fsUuid, e.getMessage());
            return false;
        }
    }
}
