package com.aicall.service;

import com.aicall.config.AiVoiceProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 商业级 SLA 运行时计数：TTFA 超标、空等事故、路径分布（进程内，重启清零）。
 */
@Service
@RequiredArgsConstructor
public class CommercialSlaMetricsService {

    private final AiVoiceProperties aiVoiceProperties;

    private final AtomicLong turnCount = new AtomicLong();
    private final AtomicLong ttfaBreachCount = new AtomicLong();
    private final AtomicLong callEndCount = new AtomicLong();
    private final AtomicLong customerHangupCount = new AtomicLong();
    private final AtomicLong aiHangupCount = new AtomicLong();
    private final ConcurrentLinkedDeque<Map<String, Object>> recentTurns = new ConcurrentLinkedDeque<>();
    private final ConcurrentLinkedDeque<Map<String, Object>> recentEnds = new ConcurrentLinkedDeque<>();

    public void recordTurn(Integer recordId, String uuid, int round, Long ttfaMs, Long ragMs, Long llmMs,
                           String path, boolean shouldHangup) {
        turnCount.incrementAndGet();
        long warnMs = Math.max(500L, aiVoiceProperties.getCommercialTtfaWarnMs());
        boolean breach = ttfaMs != null && ttfaMs > warnMs;
        if (breach) {
            ttfaBreachCount.incrementAndGet();
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("ts", System.currentTimeMillis());
        row.put("recordId", recordId);
        row.put("uuid", uuid);
        row.put("round", round);
        row.put("ttfaMs", ttfaMs);
        row.put("ragMs", ragMs);
        row.put("llmMs", llmMs);
        row.put("path", path);
        row.put("hangup", shouldHangup);
        row.put("ttfaBreach", breach);
        recentTurns.addFirst(row);
        trim(recentTurns, 50);
    }

    public void recordCallEnd(Integer recordId, String uuid, String exitReason, boolean aiHangup, int rounds) {
        callEndCount.incrementAndGet();
        if (aiHangup) {
            aiHangupCount.incrementAndGet();
        } else if (exitReason != null && exitReason.contains("customer_hangup")) {
            customerHangupCount.incrementAndGet();
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("ts", System.currentTimeMillis());
        row.put("recordId", recordId);
        row.put("uuid", uuid);
        row.put("exitReason", exitReason);
        row.put("aiHangup", aiHangup);
        row.put("rounds", rounds);
        recentEnds.addFirst(row);
        trim(recentEnds, 30);
    }

    public Map<String, Object> snapshot() {
        long turns = turnCount.get();
        long breach = ttfaBreachCount.get();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ttfaWarnMs", aiVoiceProperties.getCommercialTtfaWarnMs());
        m.put("turnCount", turns);
        m.put("ttfaBreachCount", breach);
        m.put("ttfaBreachRate", turns <= 0 ? 0.0 : Math.round(breach * 1000.0 / turns) / 10.0);
        m.put("callEndCount", callEndCount.get());
        m.put("customerHangupCount", customerHangupCount.get());
        m.put("aiHangupCount", aiHangupCount.get());
        m.put("recentTurns", new ArrayList<>(recentTurns));
        m.put("recentEnds", new ArrayList<>(recentEnds));
        return m;
    }

    private static void trim(ConcurrentLinkedDeque<?> q, int max) {
        while (q.size() > max) {
            q.pollLast();
        }
    }
}
