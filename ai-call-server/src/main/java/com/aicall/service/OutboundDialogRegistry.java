package com.aicall.service;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** 外呼对话循环生命周期：用户挂机 / call-end 时标记取消，阻塞等待中可尽快退出 */
@Component
public class OutboundDialogRegistry {

    private final ConcurrentHashMap<String, Boolean> cancelled = new ConcurrentHashMap<>();
    /** 开口世代：must-speak 超时后 bump，使迟到的 LLM/TTS 播报失效 */
    private final ConcurrentHashMap<String, AtomicLong> speakGeneration = new ConcurrentHashMap<>();
    /** 本轮已提交开播（含 TTS 合成中）：must-speak 不得再打断 */
    private final ConcurrentHashMap<String, Boolean> speakOpened = new ConcurrentHashMap<>();

    public void register(String uuid) {
        if (StringUtils.hasText(uuid)) {
            String key = uuid.trim();
            cancelled.put(key, Boolean.FALSE);
            speakGeneration.put(key, new AtomicLong(0));
            speakOpened.remove(key);
        }
    }

    public void cancel(String uuid) {
        if (StringUtils.hasText(uuid)) {
            cancelled.put(uuid.trim(), Boolean.TRUE);
        }
    }

    public void unregister(String uuid) {
        if (StringUtils.hasText(uuid)) {
            String key = uuid.trim();
            cancelled.remove(key);
            speakGeneration.remove(key);
            speakOpened.remove(key);
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

    /** 开启一轮 AI 播报前取当前世代号（与请求绑定） */
    public long currentSpeakGeneration(String uuid) {
        if (!StringUtils.hasText(uuid)) {
            return 0L;
        }
        return speakGeneration.computeIfAbsent(uuid.trim(), k -> new AtomicLong(0)).get();
    }

    /** must-speak 超时：作废进行中的 voiceTurn 播报权 */
    public long bumpSpeakGeneration(String uuid) {
        if (!StringUtils.hasText(uuid)) {
            return 0L;
        }
        String key = uuid.trim();
        speakOpened.remove(key);
        return speakGeneration.computeIfAbsent(key, k -> new AtomicLong(0)).incrementAndGet();
    }

    public boolean isSpeakGenerationCurrent(String uuid, long generation) {
        if (!StringUtils.hasText(uuid)) {
            return false;
        }
        AtomicLong cur = speakGeneration.get(uuid.trim());
        return cur != null && cur.get() == generation;
    }

    /** 流式/整段 TTS 一旦决定开播即标记（合成中也算已开口） */
    public void markSpeakOpened(String uuid) {
        if (StringUtils.hasText(uuid)) {
            speakOpened.put(uuid.trim(), Boolean.TRUE);
        }
    }

    public boolean isSpeakOpened(String uuid) {
        return StringUtils.hasText(uuid) && Boolean.TRUE.equals(speakOpened.get(uuid.trim()));
    }

    public void clearSpeakOpened(String uuid) {
        if (StringUtils.hasText(uuid)) {
            speakOpened.remove(uuid.trim());
        }
    }
}
