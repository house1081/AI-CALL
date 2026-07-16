package com.aicall.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** 对话训练全量落盘：每轮 ASR/应答/路由元数据，供后续统一优化 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DialogVoiceTrainRecordService {

    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final ObjectMapper objectMapper;

    public Path sessionDir(String sessionId) throws IOException {
        Path dir = baseDir().resolve("sessions").resolve(sanitize(sessionId));
        Files.createDirectories(dir);
        return dir;
    }

    public void logSessionStart(String sessionId, int kbId, int simCallRecordId, String mode,
                                String provider, String model, String openingText, String openingAudioUrl) {
        try {
            Map<String, Object> event = baseEvent("session_start", sessionId);
            event.put("kbId", kbId);
            event.put("simCallRecordId", simCallRecordId);
            event.put("mode", mode);
            event.put("provider", provider);
            event.put("model", model);
            event.put("openingText", openingText);
            event.put("openingAudioUrl", openingAudioUrl);
            appendEvent(sessionId, event);
            writeMeta(sessionId, event);
        } catch (Exception e) {
            log.warn("[对话训练记录] 写入 session_start 失败 session={}: {}", sessionId, e.getMessage());
        }
    }

    public void logTurn(String sessionId, int turnNo, String userText, String userAudioRelPath,
                        long asrMs, DialogVoiceTrainTurnSnapshot turn) {
        try {
            Map<String, Object> event = baseEvent("turn", sessionId);
            event.put("turnNo", turnNo);
            event.put("userText", userText != null ? userText : "");
            event.put("userAudioPath", userAudioRelPath);
            event.put("asrMs", asrMs);
            if (turn != null) {
                event.put("replyText", turn.replyText());
                event.put("replyAudioUrl", turn.replyAudioUrl());
                event.put("model", turn.model());
                event.put("handled", turn.handled());
                event.put("shouldHangup", turn.shouldHangup());
                event.put("hangupType", turn.hangupType());
                event.put("businessProbeNext", turn.businessProbeNext());
                event.put("invalidChatRounds", turn.invalidChatRounds());
                event.put("elapsedSeconds", turn.elapsedSeconds());
                event.put("turnMs", turn.turnMs());
            }
            appendEvent(sessionId, event);
        } catch (Exception e) {
            log.warn("[对话训练记录] 写入 turn 失败 session={} turn={}: {}", sessionId, turnNo, e.getMessage());
        }
    }

    public void logEmptyAsr(String sessionId, int turnNo, String userAudioRelPath, long asrMs, String reason) {
        try {
            Map<String, Object> event = baseEvent("asr_empty", sessionId);
            event.put("turnNo", turnNo);
            event.put("userAudioPath", userAudioRelPath);
            event.put("asrMs", asrMs);
            event.put("reason", reason);
            appendEvent(sessionId, event);
        } catch (Exception e) {
            log.warn("[对话训练记录] 写入 asr_empty 失败 session={}: {}", sessionId, e.getMessage());
        }
    }

    public void logSessionEnd(String sessionId, String reason, int turnCount, long durationMs) {
        try {
            Map<String, Object> event = baseEvent("session_end", sessionId);
            event.put("reason", reason);
            event.put("turnCount", turnCount);
            event.put("durationMs", durationMs);
            appendEvent(sessionId, event);
            updateMetaEnd(sessionId, event);
        } catch (Exception e) {
            log.warn("[对话训练记录] 写入 session_end 失败 session={}: {}", sessionId, e.getMessage());
        }
    }

    public List<Map<String, Object>> listRecentSessions(int limit) {
        int cap = Math.max(1, Math.min(limit, 200));
        Path sessionsRoot = baseDir().resolve("sessions");
        if (!Files.isDirectory(sessionsRoot)) {
            return List.of();
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Stream<Path> dirs = Files.list(sessionsRoot)) {
            dirs.filter(Files::isDirectory)
                    .sorted(Comparator.comparing(this::dirLastModified).reversed())
                    .limit(cap)
                    .forEach(dir -> {
                        Map<String, Object> meta = readMeta(dir);
                        if (!meta.isEmpty()) {
                            rows.add(meta);
                        } else {
                            Map<String, Object> fallback = new LinkedHashMap<>();
                            fallback.put("sessionId", dir.getFileName().toString());
                            fallback.put("endedAt", dirLastModified(dir));
                            rows.add(fallback);
                        }
                    });
        } catch (IOException e) {
            log.warn("[对话训练记录] 列表读取失败: {}", e.getMessage());
        }
        return rows;
    }

    public List<Map<String, Object>> loadSessionEvents(String sessionId) {
        Path jsonl = sessionDirSafe(sessionId).resolve("turns.jsonl");
        if (!Files.isRegularFile(jsonl)) {
            return List.of();
        }
        List<Map<String, Object>> events = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(jsonl, StandardCharsets.UTF_8)) {
                if (!StringUtils.hasText(line)) {
                    continue;
                }
                events.add(objectMapper.readValue(line, new TypeReference<Map<String, Object>>() {}));
            }
        } catch (Exception e) {
            log.warn("[对话训练记录] 读取 turns 失败 session={}: {}", sessionId, e.getMessage());
        }
        return events;
    }

    public String toRelativePath(Path absolute) {
        if (absolute == null) {
            return null;
        }
        Path base = baseDir().toAbsolutePath().normalize();
        Path abs = absolute.toAbsolutePath().normalize();
        if (abs.startsWith(base)) {
            return base.relativize(abs).toString().replace('\\', '/');
        }
        return abs.toString();
    }

    private Path sessionDirSafe(String sessionId) {
        return baseDir().resolve("sessions").resolve(sanitize(sessionId));
    }

    private Path baseDir() {
        return Path.of(System.getProperty("user.dir")).resolve("uploads").resolve("voice-train")
                .toAbsolutePath().normalize();
    }

    private static String sanitize(String sessionId) {
        return sessionId.replaceAll("[^a-zA-Z0-9_-]", "_");
    }

    private Map<String, Object> baseEvent(String type, String sessionId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("sessionId", sessionId);
        m.put("ts", Instant.now().toString());
        m.put("day", LocalDate.now(ZONE).format(DAY_FMT));
        return m;
    }

    private void appendEvent(String sessionId, Map<String, Object> event) throws IOException {
        Path dir = sessionDir(sessionId);
        Path jsonl = dir.resolve("turns.jsonl");
        ObjectMapper pretty = objectMapper.copy().disable(SerializationFeature.INDENT_OUTPUT);
        String line = pretty.writeValueAsString(event);
        Files.writeString(jsonl, line + System.lineSeparator(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private void writeMeta(String sessionId, Map<String, Object> startEvent) throws IOException {
        Path meta = sessionDir(sessionId).resolve("meta.json");
        Map<String, Object> metaMap = new LinkedHashMap<>(startEvent);
        metaMap.put("startedAt", startEvent.get("ts"));
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(meta.toFile(), metaMap);
    }

    private void updateMetaEnd(String sessionId, Map<String, Object> endEvent) throws IOException {
        Path meta = sessionDir(sessionId).resolve("meta.json");
        Map<String, Object> metaMap = new LinkedHashMap<>();
        if (Files.isRegularFile(meta)) {
            metaMap.putAll(objectMapper.readValue(meta.toFile(), new TypeReference<Map<String, Object>>() {}));
        }
        metaMap.put("endedAt", endEvent.get("ts"));
        metaMap.put("endReason", endEvent.get("reason"));
        metaMap.put("turnCount", endEvent.get("turnCount"));
        metaMap.put("durationMs", endEvent.get("durationMs"));
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(meta.toFile(), metaMap);
    }

    private Map<String, Object> readMeta(Path sessionDir) {
        Path meta = sessionDir.resolve("meta.json");
        if (!Files.isRegularFile(meta)) {
            return Map.of();
        }
        try {
            Map<String, Object> m = objectMapper.readValue(meta.toFile(), new TypeReference<Map<String, Object>>() {});
            m.putIfAbsent("sessionId", sessionDir.getFileName().toString());
            return m;
        } catch (Exception e) {
            return Map.of("sessionId", sessionDir.getFileName().toString());
        }
    }

    private long dirLastModified(Path dir) {
        try {
            Path jsonl = dir.resolve("turns.jsonl");
            if (Files.exists(jsonl)) {
                return Files.getLastModifiedTime(jsonl).toMillis();
            }
            return Files.getLastModifiedTime(dir).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    public record DialogVoiceTrainTurnSnapshot(
            String replyText,
            String replyAudioUrl,
            String model,
            boolean handled,
            boolean shouldHangup,
            String hangupType,
            Boolean businessProbeNext,
            Integer invalidChatRounds,
            Integer elapsedSeconds,
            long turnMs) {
    }
}
