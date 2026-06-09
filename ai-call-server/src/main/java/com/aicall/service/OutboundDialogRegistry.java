package com.aicall.service;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.concurrent.ConcurrentHashMap;

/** 外呼对话循环生命周期：用户挂机 / call-end 时标记取消，阻塞等待中可尽快退出 */
@Component
public class OutboundDialogRegistry {

    private final ConcurrentHashMap<String, Boolean> cancelled = new ConcurrentHashMap<>();

    public void register(String uuid) {
        if (StringUtils.hasText(uuid)) {
            cancelled.put(uuid.trim(), Boolean.FALSE);
        }
    }

    public void cancel(String uuid) {
        if (StringUtils.hasText(uuid)) {
            cancelled.put(uuid.trim(), Boolean.TRUE);
        }
    }

    public void unregister(String uuid) {
        if (StringUtils.hasText(uuid)) {
            cancelled.remove(uuid.trim());
        }
    }

    public boolean isCancelled(String uuid) {
        return StringUtils.hasText(uuid) && Boolean.TRUE.equals(cancelled.get(uuid.trim()));
    }

    public boolean shouldContinue(String uuid) {
        if (!StringUtils.hasText(uuid)) {
            return false;
        }
        return !isCancelled(uuid);
    }
}
