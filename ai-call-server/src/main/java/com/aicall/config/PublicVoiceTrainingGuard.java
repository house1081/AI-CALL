package com.aicall.config;

import com.aicall.common.BizException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** 公开对话训练接口：按 IP 限流，防止滥用 */
@Component
public class PublicVoiceTrainingGuard {

    private static final int MAX_START_PER_MINUTE = 20;
    private static final int MAX_TURN_PER_MINUTE = 120;
    private static final long WINDOW_MS = 60_000L;

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    public void checkStart(HttpServletRequest request) {
        check(clientKey(request), "start", MAX_START_PER_MINUTE);
    }

    public void checkTurn(HttpServletRequest request) {
        check(clientKey(request), "turn", MAX_TURN_PER_MINUTE);
    }

    private void check(String key, String action, int max) {
        long now = System.currentTimeMillis();
        Window w = windows.computeIfAbsent(key + ":" + action, k -> new Window());
        synchronized (w) {
            if (now - w.windowStartMs > WINDOW_MS) {
                w.windowStartMs = now;
                w.count.set(0);
            }
            if (w.count.incrementAndGet() > max) {
                throw new BizException(429, "请求过于频繁，请稍后再试");
            }
        }
    }

    private static String clientKey(HttpServletRequest request) {
        if (request == null) {
            return "unknown";
        }
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        return request.getRemoteAddr() != null ? request.getRemoteAddr() : "unknown";
    }

    private static final class Window {
        volatile long windowStartMs = System.currentTimeMillis();
        final AtomicInteger count = new AtomicInteger(0);
    }
}
