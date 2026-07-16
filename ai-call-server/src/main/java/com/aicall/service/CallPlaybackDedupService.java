package com.aicall.service;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 防止同一通话内短时间重复播放同一段录音/话术 */
@Service
public class CallPlaybackDedupService {

    private static final long DEDUP_WINDOW_MS = 12_000L;

    private record LastPlay(String text, String wavPath, long atMs) {
    }

    private final Map<Integer, LastPlay> lastByCall = new ConcurrentHashMap<>();

    public boolean shouldSkip(Integer callRecordId, String text, String wavPath) {
        if (callRecordId == null) {
            return false;
        }
        LastPlay prev = lastByCall.get(callRecordId);
        if (prev == null || System.currentTimeMillis() - prev.atMs > DEDUP_WINDOW_MS) {
            return false;
        }
        if (StringUtils.hasText(text) && text.trim().equals(prev.text)) {
            return true;
        }
        return StringUtils.hasText(wavPath) && wavPath.trim().equals(prev.wavPath);
    }

    public void record(Integer callRecordId, String text, String wavPath) {
        if (callRecordId == null) {
            return;
        }
        lastByCall.put(callRecordId, new LastPlay(
                StringUtils.hasText(text) ? text.trim() : "",
                StringUtils.hasText(wavPath) ? wavPath.trim() : "",
                System.currentTimeMillis()));
    }

    public void clear(Integer callRecordId) {
        if (callRecordId != null) {
            lastByCall.remove(callRecordId);
        }
    }
}
