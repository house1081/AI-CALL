package com.aicall.service;

import com.aicall.config.AiVoiceProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.*;

/**
 * 外呼对话专用 LLM 线程池：避免在 dialog-loop 线程上阻塞 HTTP，可与录音/播报并行调度。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DialogLlmExecutorService {

    private final AiVoiceProperties aiVoiceProperties;

    private ExecutorService executor;

    @PostConstruct
    void initPool() {
        int size = Math.max(2, Math.min(32, aiVoiceProperties.getDialogLlmPoolSize()));
        executor = Executors.newFixedThreadPool(size, r -> {
            Thread t = new Thread(r, "dialog-llm");
            t.setDaemon(true);
            return t;
        });
        log.info("[对话LLM] 线程池已初始化 size={}", size);
    }

    public <T> T run(SupplierThrowing<T> task) throws Exception {
        int timeoutSec = Math.max(5, Math.min(60, aiVoiceProperties.getDialogLlmTimeoutSec()));
        return run(task, timeoutSec);
    }

    /** @param timeoutSec 仅约束本任务（应为 LLM 调用，勿把 TTS/播报包进来） */
    public <T> T run(SupplierThrowing<T> task, int timeoutSec) throws Exception {
        if (!aiVoiceProperties.isDialogLlmAsync()) {
            return task.get();
        }
        int sec = Math.max(5, Math.min(60, timeoutSec));
        Future<T> future = executor.submit(() -> {
            try {
                return task.get();
            } catch (Exception e) {
                if (e instanceof RuntimeException re) {
                    throw re;
                }
                throw new RuntimeException(e);
            }
        });
        try {
            return future.get(sec, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new TimeoutException("大模型调用超时 " + sec + "s");
        } catch (ExecutionException e) {
            Throwable c = e.getCause();
            if (c instanceof Exception ex) {
                throw ex;
            }
            throw new RuntimeException(c != null ? c : e);
        }
    }

    @FunctionalInterface
    public interface SupplierThrowing<T> {
        T get() throws Exception;
    }
}
