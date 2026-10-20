package com.aicall.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

/**
 * 商业级通话指标：一行 JSON，便于 grep / 采集。
 * 前缀固定为 {@code [通话指标]}。
 */
public final class CallMetricsLog {

    private static final Logger log = LoggerFactory.getLogger("com.aicall.metrics");

    private CallMetricsLog() {
    }

    /** 单轮：听音→识别→决策→首句开播 */
    public static void turn(Integer callRecordId, String fsUuid, int round,
                            long listenMs, long asrMs, Long ttfaMs, Long ragMs, Long llmMs,
                            long turnTotalMs, String model, String path,
                            boolean shouldHangup, int replyLen) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("{\"type\":\"turn\"");
        appendNum(sb, "recordId", callRecordId);
        appendStr(sb, "uuid", shortUuid(fsUuid));
        appendNum(sb, "round", round);
        appendNum(sb, "listenMs", listenMs);
        appendNum(sb, "asrMs", asrMs);
        appendNum(sb, "ttfaMs", ttfaMs);
        appendNum(sb, "ragMs", ragMs);
        appendNum(sb, "llmMs", llmMs);
        appendNum(sb, "turnTotalMs", turnTotalMs);
        appendStr(sb, "model", model);
        appendStr(sb, "path", path);
        sb.append(",\"hangup\":").append(shouldHangup);
        appendNum(sb, "replyLen", replyLen);
        sb.append('}');
        log.info("[通话指标] {}", sb);
    }

    /** 整通结束摘要 */
    public static void callEnd(Integer callRecordId, String fsUuid, int rounds, int durationSec,
                               String exitReason, boolean aiHangup, int callStatus) {
        StringBuilder sb = new StringBuilder(192);
        sb.append("{\"type\":\"call_end\"");
        appendNum(sb, "recordId", callRecordId);
        appendStr(sb, "uuid", shortUuid(fsUuid));
        appendNum(sb, "rounds", rounds);
        appendNum(sb, "durationSec", durationSec);
        appendStr(sb, "exitReason", exitReason);
        sb.append(",\"aiHangup\":").append(aiHangup);
        appendNum(sb, "callStatus", callStatus);
        sb.append('}');
        log.info("[通话指标] {}", sb);
    }

    private static String shortUuid(String fsUuid) {
        if (!StringUtils.hasText(fsUuid)) {
            return "-";
        }
        return fsUuid.length() > 8 ? fsUuid.substring(0, 8) : fsUuid;
    }

    private static void appendNum(StringBuilder sb, String key, Number value) {
        sb.append(",\"").append(key).append("\":");
        if (value == null) {
            sb.append("null");
        } else {
            sb.append(value);
        }
    }

    private static void appendStr(StringBuilder sb, String key, String value) {
        sb.append(",\"").append(key).append("\":\"");
        if (value != null) {
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                if (c == '"' || c == '\\') {
                    sb.append('\\').append(c);
                } else if (c == '\n' || c == '\r') {
                    sb.append(' ');
                } else {
                    sb.append(c);
                }
            }
        }
        sb.append('"');
    }
}
