package com.aicall.service;

import com.aicall.common.AiModelProvider;
import com.aicall.common.BizException;
import com.aicall.entity.AiModelConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

@Slf4j
@Service
@RequiredArgsConstructor
public class LlmInvokeService {

    private final RestTemplate llmRestTemplate;
    private final ObjectMapper objectMapper;
    private final Map<String, WenxinToken> wenxinTokenCache = new ConcurrentHashMap<>();
    private final HttpClient streamClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    public Map<String, Object> healthCheck(AiModelConfig cfg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("provider", cfg.getProvider());
        m.put("model", cfg.getModelName());
        m.put("baseUrl", cfg.getBaseUrl());
        try {
            switch (cfg.getProvider()) {
                case AiModelProvider.OLLAMA -> checkOllama(cfg, m);
                case AiModelProvider.QWEN -> checkQwen(cfg, m);
                case AiModelProvider.WENXIN -> checkWenxin(cfg, m);
                default -> throw new BizException("不支持的提供方: " + cfg.getProvider());
            }
        } catch (BizException e) {
            m.put("ok", false);
            m.put("error", e.getMessage());
        } catch (Exception e) {
            log.warn("模型健康检查失败 provider={}: {}", cfg.getProvider(), e.getMessage());
            m.put("ok", false);
            m.put("error", e.getMessage());
        }
        return m;
    }

    public String chat(List<Map<String, String>> messages, AiModelConfig cfg) {
        return chatStreaming(messages, cfg, null);
    }

    /**
     * 流式调用大模型；{@code onDelta} 每收到一段文本回调一次，返回完整拼接结果。
     */
    public String chatStreaming(List<Map<String, String>> messages, AiModelConfig cfg,
                                Consumer<String> onDelta) {
        return switch (cfg.getProvider()) {
            case AiModelProvider.OLLAMA -> chatOllamaStream(messages, cfg, onDelta);
            case AiModelProvider.QWEN -> chatQwenStream(messages, cfg, onDelta);
            case AiModelProvider.WENXIN -> {
                String full = chatWenxin(messages, cfg);
                if (onDelta != null && StringUtils.hasText(full)) {
                    onDelta.accept(full);
                }
                yield full;
            }
            default -> throw new BizException("不支持的提供方: " + cfg.getProvider());
        };
    }

    private void checkOllama(AiModelConfig cfg, Map<String, Object> m) {
        String url = normalizeBase(cfg.getBaseUrl()) + "/api/tags";
        ResponseEntity<String> resp = llmRestTemplate.getForEntity(url, String.class);
        boolean ok = resp.getStatusCode().is2xxSuccessful();
        m.put("ok", ok);
        if (ok && resp.getBody() != null) {
            JsonNode root = parseJson(resp.getBody());
            List<String> models = new ArrayList<>();
            if (root.has("models")) {
                for (JsonNode n : root.get("models")) {
                    if (n.has("name")) {
                        models.add(n.get("name").asText());
                    }
                }
            }
            m.put("models", models);
            m.put("modelReady", models.stream().anyMatch(x ->
                    x.equals(cfg.getModelName()) || x.startsWith(cfg.getModelName() + ":")));
        }
    }

    private void checkQwen(AiModelConfig cfg, Map<String, Object> m) {
        if (!StringUtils.hasText(cfg.getApiKey())) {
            throw new BizException("未配置 API Key");
        }
        List<Map<String, String>> probe = List.of(Map.of("role", "user", "content", "ping"));
        String reply = chatQwen(probe, cfg);
        m.put("ok", StringUtils.hasText(reply));
        m.put("sample", reply != null && reply.length() > 30 ? reply.substring(0, 30) + "..." : reply);
    }

    private void checkWenxin(AiModelConfig cfg, Map<String, Object> m) {
        if (!StringUtils.hasText(cfg.getApiKey()) || !StringUtils.hasText(cfg.getSecretKey())) {
            throw new BizException("未配置 API Key / Secret Key");
        }
        String token = fetchWenxinToken(cfg);
        m.put("ok", StringUtils.hasText(token));
        m.put("tokenCached", true);
    }

    private String chatOllama(List<Map<String, String>> messages, AiModelConfig cfg) {
        return chatOllamaStream(messages, cfg, null);
    }

    private String chatOllamaStream(List<Map<String, String>> messages, AiModelConfig cfg,
                                    Consumer<String> onDelta) {
        String url = normalizeBase(cfg.getBaseUrl()) + "/api/chat";
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", cfg.getModelName());
        body.put("messages", messages);
        body.put("stream", onDelta != null);
        body.put("options", Map.of(
                "num_predict", cfg.getMaxTokens(),
                "temperature", toDouble(cfg.getTemperature())
        ));
        if (onDelta == null) {
            JsonNode root = postJson(url, body, null);
            JsonNode msg = root.path("message").path("content");
            if (!msg.isTextual()) {
                throw new BizException("Ollama 响应无 content");
            }
            return msg.asText();
        }
        StringBuilder full = new StringBuilder();
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(Math.max(30, cfg.getReadTimeoutMs() / 1000)))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();
            HttpResponse<InputStream> resp = streamClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                throw new BizException("Ollama 流式 HTTP " + resp.statusCode());
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!StringUtils.hasText(line)) {
                        continue;
                    }
                    JsonNode root = objectMapper.readTree(line);
                    JsonNode content = root.path("message").path("content");
                    if (content.isTextual()) {
                        String delta = content.asText();
                        if (StringUtils.hasText(delta)) {
                            full.append(delta);
                            onDelta.accept(delta);
                        }
                    }
                }
            }
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException("Ollama 流式调用失败: " + e.getMessage());
        }
        return full.toString();
    }

    private String chatQwen(List<Map<String, String>> messages, AiModelConfig cfg) {
        return chatQwenStream(messages, cfg, null);
    }

    private String chatQwenStream(List<Map<String, String>> messages, AiModelConfig cfg,
                                  Consumer<String> onDelta) {
        int maxAttempts = 2;
        Exception last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return chatQwenStreamOnce(messages, cfg, onDelta);
            } catch (BizException e) {
                throw e;
            } catch (Exception e) {
                last = e;
                if (attempt < maxAttempts && isRetryableNetwork(e)) {
                    log.warn("通义千问流式网络异常，重试 {}/{}: {}", attempt, maxAttempts, e.getMessage());
                    try {
                        Thread.sleep(500L * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        throw new BizException("通义千问流式调用失败: " + (last != null ? last.getMessage() : "unknown"));
    }

    private static boolean isRetryableNetwork(Throwable e) {
        String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
        return msg.contains("connection reset") || msg.contains("connection refused")
                || msg.contains("timed out") || msg.contains("timeout")
                || msg.contains("broken pipe") || msg.contains("goaway");
    }

    private String chatQwenStreamOnce(List<Map<String, String>> messages, AiModelConfig cfg,
                                      Consumer<String> onDelta) throws Exception {
        if (!StringUtils.hasText(cfg.getApiKey())) {
            throw new BizException("通义千问未配置 API Key");
        }
        String url = normalizeBase(cfg.getBaseUrl());
        if (!url.endsWith("/chat/completions")) {
            url = url + "/chat/completions";
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", cfg.getModelName());
        body.put("messages", messages);
        body.put("max_tokens", cfg.getMaxTokens());
        body.put("temperature", toDouble(cfg.getTemperature()));
        if (cfg.getTopP() != null && cfg.getTopP() > 0 && cfg.getTopP() <= 1.0) {
            body.put("top_p", cfg.getTopP());
        }
        body.put("stream", onDelta != null);
        if (onDelta == null) {
            HttpHeaders headers = bearerHeaders(cfg.getApiKey());
            JsonNode root = postJson(url, body, headers);
            JsonNode content = root.path("choices").path(0).path("message").path("content");
            if (!content.isTextual()) {
                throw new BizException("通义千问响应无 content");
            }
            return content.asText();
        }
        StringBuilder full = new StringBuilder();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(Math.max(30, cfg.getReadTimeoutMs() / 1000)))
                .header("Authorization", "Bearer " + cfg.getApiKey().trim())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();
        HttpResponse<InputStream> resp = streamClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            String err = resp.body() != null ? new String(resp.body().readAllBytes(), StandardCharsets.UTF_8) : "";
            throw new BizException("通义千问流式 HTTP " + resp.statusCode() + extractApiError(err));
        }
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data:")) {
                    continue;
                }
                String data = line.substring(5).trim();
                if ("[DONE]".equals(data) || !StringUtils.hasText(data)) {
                    continue;
                }
                JsonNode root = objectMapper.readTree(data);
                JsonNode delta = root.path("choices").path(0).path("delta").path("content");
                if (!delta.isTextual()) {
                    continue;
                }
                String chunk = delta.asText();
                if (StringUtils.hasText(chunk)) {
                    full.append(chunk);
                    onDelta.accept(chunk);
                }
            }
        }
        return full.toString();
    }

    private String chatWenxin(List<Map<String, String>> messages, AiModelConfig cfg) {
        String token = fetchWenxinToken(cfg);
        String base = normalizeBase(cfg.getBaseUrl());
        String url = base.contains("?")
                ? base + "&access_token=" + token
                : base + "?access_token=" + token;
        List<Map<String, String>> wenxinMsgs = toWenxinMessages(messages);
        if (wenxinMsgs.isEmpty()) {
            throw new BizException("文心一言消息为空");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("messages", wenxinMsgs);
        body.put("temperature", toDouble(cfg.getTemperature()));
        body.put("max_output_tokens", cfg.getMaxTokens());
        JsonNode root = postJson(url, body, null);
        if (root.has("error_code")) {
            throw new BizException("文心一言错误: " + root.path("error_msg").asText("unknown"));
        }
        JsonNode result = root.path("result");
        if (!result.isTextual()) {
            throw new BizException("文心一言响应无 result");
        }
        return result.asText();
    }

    private List<Map<String, String>> toWenxinMessages(List<Map<String, String>> messages) {
        List<Map<String, String>> out = new ArrayList<>();
        for (Map<String, String> m : messages) {
            if ("system".equals(m.get("role"))) {
                continue;
            }
            out.add(Map.of("role", m.get("role"), "content", m.get("content")));
        }
        String system = messages.stream()
                .filter(x -> "system".equals(x.get("role")))
                .map(x -> x.get("content"))
                .findFirst()
                .orElse(null);
        if (StringUtils.hasText(system) && !out.isEmpty()) {
            Map<String, String> first = new LinkedHashMap<>(out.get(0));
            if ("user".equals(first.get("role"))) {
                first.put("content", system + "\n\n" + first.get("content"));
                out.set(0, first);
            }
        }
        return out;
    }

    private String fetchWenxinToken(AiModelConfig cfg) {
        String cacheKey = cfg.getApiKey() + ":" + cfg.getSecretKey();
        WenxinToken cached = wenxinTokenCache.get(cacheKey);
        if (cached != null && cached.expireAt > System.currentTimeMillis()) {
            return cached.token;
        }
        String url = UriComponentsBuilder
                .fromHttpUrl("https://aip.baidubce.com/oauth/2.0/token")
                .queryParam("grant_type", "client_credentials")
                .queryParam("client_id", cfg.getApiKey())
                .queryParam("client_secret", cfg.getSecretKey())
                .toUriString();
        ResponseEntity<String> resp = llmRestTemplate.getForEntity(url, String.class);
        if (!resp.getStatusCode().is2xxSuccessful() || resp.getBody() == null) {
            throw new BizException("获取文心 access_token 失败");
        }
        JsonNode root = parseJson(resp.getBody());
        if (!root.has("access_token")) {
            throw new BizException("文心 token 响应异常: " + root.path("error_description").asText(""));
        }
        String token = root.get("access_token").asText();
        long expiresIn = root.has("expires_in") ? root.get("expires_in").asLong(2592000) : 2592000;
        wenxinTokenCache.put(cacheKey, new WenxinToken(token, System.currentTimeMillis() + (expiresIn - 60) * 1000));
        return token;
    }

    private JsonNode postJson(String url, Object body, HttpHeaders extra) {
        HttpHeaders headers = extra != null ? extra : new HttpHeaders();
        if (!headers.containsKey(HttpHeaders.CONTENT_TYPE)) {
            headers.setContentType(MediaType.APPLICATION_JSON);
        }
        try {
            ResponseEntity<String> resp = llmRestTemplate.exchange(
                    url, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
            String respBody = resp.getBody();
            if (!resp.getStatusCode().is2xxSuccessful() || respBody == null) {
                throw new BizException("模型 HTTP 异常: " + resp.getStatusCode()
                        + extractApiError(respBody));
            }
            return parseJson(respBody);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException("调用模型失败: " + e.getMessage());
        }
    }

    private String extractApiError(String body) {
        if (!StringUtils.hasText(body)) {
            return "";
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            if (root.has("error")) {
                JsonNode err = root.get("error");
                if (err.isTextual()) {
                    return " — " + err.asText();
                }
                if (err.has("message")) {
                    return " — " + err.get("message").asText();
                }
            }
            if (root.has("message")) {
                return " — " + root.get("message").asText();
            }
            if (root.has("error_msg")) {
                return " — " + root.get("error_msg").asText();
            }
        } catch (Exception ignored) {
            // ignore parse errors
        }
        return "";
    }

    private HttpHeaders bearerHeaders(String apiKey) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setBearerAuth(apiKey.trim());
        return h;
    }

    private double toDouble(BigDecimal v) {
        return v != null ? v.doubleValue() : 0.7;
    }

    private String normalizeBase(String base) {
        String b = base.trim();
        if (b.endsWith("/")) {
            return b.substring(0, b.length() - 1);
        }
        return b;
    }

    private JsonNode parseJson(String body) {
        try {
            return objectMapper.readTree(body);
        } catch (Exception e) {
            throw new BizException("解析模型响应失败");
        }
    }

    private record WenxinToken(String token, long expireAt) {}
}
