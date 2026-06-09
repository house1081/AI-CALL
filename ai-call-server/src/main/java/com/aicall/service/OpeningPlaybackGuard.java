package com.aicall.service;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.concurrent.ConcurrentHashMap;

/** 防止同一通道重复播放预录开场白 */
@Component
public class OpeningPlaybackGuard {

    private final ConcurrentHashMap<String, Boolean> played = new ConcurrentHashMap<>();

    public boolean markPlayedIfAbsent(String uuid) {
        if (!StringUtils.hasText(uuid)) {
            return false;
        }
        return played.putIfAbsent(uuid.trim(), Boolean.TRUE) == null;
    }

    public boolean hasPlayed(String uuid) {
        return StringUtils.hasText(uuid) && played.containsKey(uuid.trim());
    }

    public void clear(String uuid) {
        if (StringUtils.hasText(uuid)) {
            played.remove(uuid.trim());
        }
    }
}
