package com.aicall.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Paraformer 实时识别 WebSocket（本地 8k wav 直传，无需公网 file_url）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ParaformerRealtimeAsrService {

    private static final String WS_URL = "wss://dashscope.aliyuncs.com/api-ws/v1/inference";
    private static final int CHUNK_BYTES_8K = 3200;

    private final ObjectMapper objectMapper;

    public String recognize(Path wavFile, String apiKey, String model, int timeoutSec) {
        if (wavFile == null || !Files.exists(wavFile) || !StringUtils.hasText(apiKey)) {
            return "";
        }
        long start = System.currentTimeMillis();
        String taskId = UUID.randomUUID().toString().replace("-", "").substring(0, 32);
        CountDownLatch done = new CountDownLatch(1);
        AtomicBoolean taskStarted = new AtomicBoolean(false);
        AtomicReference<String> text = new AtomicReference<>("");
        AtomicReference<String> error = new AtomicReference<>();
        HttpClient httpClient = HttpClient.newHttpClient();
        WebSocket[] holder = new WebSocket[1];
        try {
            byte[] audio = Files.readAllBytes(wavFile);
            CompletableFuture<WebSocket> future = httpClient.newWebSocketBuilder()
                    .header("Authorization", "bearer " + apiKey)
                    .buildAsync(URI.create(WS_URL), new WebSocket.Listener() {
                        @Override
                        public void onOpen(WebSocket webSocket) {
                            holder[0] = webSocket;
                            webSocket.request(1);
                            try {
                                sendJson(webSocket, runTaskJson(taskId, model));
                            } catch (Exception e) {
                                error.set(e.getMessage());
                                done.countDown();
                            }
                        }

                        @Override
                        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                            try {
                                JsonNode root = objectMapper.readTree(data.toString());
                                String event = root.path("header").path("event").asText("");
                                switch (event) {
                                    case "task-started" -> {
                                        taskStarted.set(true);
                                        streamAudio(webSocket, audio, taskId);
                                    }
                                    case "result-generated" -> {
                                        JsonNode sentence = root.path("payload").path("output").path("sentence");
                                        String t = sentence.path("text").asText("").trim();
                                        if (StringUtils.hasText(t)
                                                && (sentence.path("sentence_end").asBoolean(false) || !StringUtils.hasText(text.get()))) {
                                            text.set(t);
                                        }
                                    }
                                    case "task-finished" -> done.countDown();
                                    case "task-failed" -> {
                                        error.set(root.path("header").path("error_message").asText("task-failed"));
                                        done.countDown();
                                    }
                                    default -> {
                                    }
                                }
                            } catch (Exception e) {
                                error.set(e.getMessage());
                                done.countDown();
                            }
                            webSocket.request(1);
                            return CompletableFuture.completedFuture(null);
                        }

                        @Override
                        public void onError(WebSocket webSocket, Throwable thr) {
                            error.set(thr.getMessage());
                            done.countDown();
                        }

                        @Override
                        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                            done.countDown();
                            return CompletableFuture.completedFuture(null);
                        }
                    });
            future.join();
            if (!done.await(timeoutSec, TimeUnit.SECONDS)) {
                error.set("timeout " + timeoutSec + "s");
            }
            WebSocket ws = holder[0];
            if (ws != null && !ws.isOutputClosed()) {
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye");
            }
            if (StringUtils.hasText(error.get())) {
                log.warn("[ASR] Paraformer WS 失败 model={} err={}", model, error.get());
                return "";
            }
            String out = text.get() != null ? text.get() : "";
            log.info("[ASR] Paraformer WS model={} 耗时={}ms len={}",
                    model, System.currentTimeMillis() - start, out.length());
            return out;
        } catch (Exception e) {
            log.warn("[ASR] Paraformer WS 异常 model={}: {}", model, e.getMessage());
            return "";
        }
    }

    private void streamAudio(WebSocket webSocket, byte[] audio, String taskId) {
        Thread t = new Thread(() -> {
            try {
                if (audio.length <= 128_000) {
                    webSocket.sendBinary(ByteBuffer.wrap(audio), true);
                } else {
                    for (int offset = 0; offset < audio.length; offset += CHUNK_BYTES_8K) {
                        int end = Math.min(offset + CHUNK_BYTES_8K, audio.length);
                        byte[] chunk = new byte[end - offset];
                        System.arraycopy(audio, offset, chunk, 0, chunk.length);
                        webSocket.sendBinary(ByteBuffer.wrap(chunk), true);
                    }
                }
                try {
                    sendJson(webSocket, finishTaskJson(taskId));
                } catch (Exception e) {
                    log.debug("[ASR] Paraformer finish-task 失败: {}", e.getMessage());
                }
            } catch (Exception e) {
                log.debug("[ASR] Paraformer 送音频失败: {}", e.getMessage());
            }
        }, "paraformer-asr-send");
        t.setDaemon(true);
        t.start();
    }

    private static void sendJson(WebSocket webSocket, String json) {
        webSocket.sendText(json, true);
    }

    private String runTaskJson(String taskId, String model) throws Exception {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("sample_rate", 8000);
        parameters.put("format", "wav");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("task_group", "audio");
        payload.put("task", "asr");
        payload.put("function", "recognition");
        payload.put("model", model);
        payload.put("parameters", parameters);
        payload.put("input", Map.of());
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("action", "run-task");
        header.put("task_id", taskId);
        header.put("streaming", "duplex");
        return objectMapper.writeValueAsString(Map.of("header", header, "payload", payload));
    }

    private String finishTaskJson(String taskId) throws Exception {
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("action", "finish-task");
        header.put("task_id", taskId);
        header.put("streaming", "duplex");
        return objectMapper.writeValueAsString(Map.of(
                "header", header,
                "payload", Map.of("input", Map.of())));
    }
}
