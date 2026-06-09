package com.aicall.service;

import com.aicall.config.AiVoiceProperties;
import com.aicall.util.TelephonyWavUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * DashScope CosyVoice HTTP TTS。
 * <p>
 * 非流式 JSON 仅返回 OSS URL；要直接拿二进制须走 SSE（等同 SDK {@code callAndReturnAudio}），
 * 从 {@code output.audio.data} 拼接 Base64，禁止再下载 URL。
 */
@Slf4j
@Service
public class DashScopeVoiceTtsService {

    private static final String TTS_URL =
            "https://dashscope.aliyuncs.com/api/v1/services/audio/tts/SpeechSynthesizer";

    private final AiVoiceProperties aiVoiceProperties;
    private final DashScopeApiKeyResolver dashScopeApiKeyResolver;
    private final VoiceRuntimeSettingsService voiceRuntimeSettingsService;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    private final Object globalTtsLock = new Object();
    private final AtomicLong lastTtsRequestAt = new AtomicLong(0);
    private volatile long rateLimitCooldownUntilMs = 0L;

    public DashScopeVoiceTtsService(AiVoiceProperties aiVoiceProperties,
                                    DashScopeApiKeyResolver dashScopeApiKeyResolver,
                                    VoiceRuntimeSettingsService voiceRuntimeSettingsService,
                                    ObjectMapper objectMapper) {
        this.aiVoiceProperties = aiVoiceProperties;
        this.dashScopeApiKeyResolver = dashScopeApiKeyResolver;
        this.voiceRuntimeSettingsService = voiceRuntimeSettingsService;
        this.objectMapper = objectMapper;
    }

    public boolean isAvailable() {
        return dashScopeApiKeyResolver.isConfigured();
    }

    public Path synthesizeToFile(Path out, String text) {
        return synthesizeToFile(out, text, null);
    }

    /** 指定 CosyVoice voice_id 合成（多音色固定话术预生成） */
    public Path synthesizeToFile(Path out, String text, String voiceIdOverride) {
        String apiKey = dashScopeApiKeyResolver.resolve();
        if (!StringUtils.hasText(apiKey) || !StringUtils.hasText(text)) {
            return null;
        }
        synchronized (globalTtsLock) {
            int maxRetries = Math.max(0, aiVoiceProperties.getTtsRateLimitRetries());
            for (int attempt = 0; attempt <= maxRetries; attempt++) {
                try {
                    throttleBeforeRequest();
                    byte[] raw = useSseStream() ? synthesizeAudioBytesSse(apiKey, text, voiceIdOverride)
                            : synthesizeAudioBytesSync(apiKey, text, voiceIdOverride);
                    if (raw == null || raw.length < 320) {
                        log.warn("CosyVoice TTS 无有效音频字节 len={}", raw != null ? raw.length : 0);
                        return null;
                    }
                    byte[] telephony = toTelephonyWav(raw);
                    telephony = TelephonyWavUtil.normalizeWavPeak(telephony, 0.9);
                    Files.write(out, telephony);
                    log.info("CosyVoice 已合成 8k/mono/16bit wav {} bytes model={} voice={}",
                            telephony.length, resolveTtsModel(), resolveVoice(voiceIdOverride));
                    return out;
                } catch (Exception e) {
                    boolean rateLimited = isRateLimitError(e);
                    if (rateLimited) {
                        markRateLimitCooldown();
                    }
                    if (rateLimited && attempt < maxRetries) {
                        long wait = (long) (1500 * Math.pow(2, attempt));
                        log.warn("CosyVoice 限流(428)，{}ms 后重试 {}/{}", wait, attempt + 1, maxRetries);
                        sleepQuiet(wait);
                        continue;
                    }
                    if (isRetryableNetwork(e) && attempt < maxRetries) {
                        log.warn("CosyVoice 网络异常，{}ms 后重试 {}/{}: {}",
                                400L * (attempt + 1), attempt + 1, maxRetries, e.getMessage());
                        sleepQuiet(400L * (attempt + 1));
                        continue;
                    }
                    log.warn("CosyVoice TTS 异常: {}", e.getMessage());
                    return null;
                }
            }
        }
        return null;
    }

    private void markRateLimitCooldown() {
        long cooldown = Math.max(3000, aiVoiceProperties.getTtsRateLimitCooldownMs());
        rateLimitCooldownUntilMs = System.currentTimeMillis() + cooldown;
    }

    private void throttleBeforeRequest() throws InterruptedException {
        long now = System.currentTimeMillis();
        long cooldownWait = rateLimitCooldownUntilMs - now;
        if (cooldownWait > 0) {
            log.info("CosyVoice 全局限流冷却，等待 {}ms", cooldownWait);
            Thread.sleep(cooldownWait);
        }
        int minGap = Math.max(0, aiVoiceProperties.getTtsMinIntervalMs());
        if (minGap > 0) {
            long prev = lastTtsRequestAt.get();
            long gapWait = minGap - (System.currentTimeMillis() - prev);
            if (gapWait > 0) {
                Thread.sleep(gapWait);
            }
        }
        lastTtsRequestAt.set(System.currentTimeMillis());
    }

    private static boolean isRateLimitError(Exception e) {
        String msg = e.getMessage();
        return msg != null && (msg.contains("428") || msg.contains("Throttling") || msg.contains("rate limit"));
    }

    private static boolean isRetryableNetwork(Exception e) {
        String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
        return msg.contains("connection reset") || msg.contains("connection refused")
                || msg.contains("timed out") || msg.contains("timeout")
                || msg.contains("broken pipe") || msg.contains("goaway");
    }

    private static void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 供 TTS 短语缓存、开场白 meta 签名（含模型与音色） */
    public String voiceCacheSignature() {
        return voiceCacheSignature(null);
    }

    public String voiceCacheSignature(String voiceIdOverride) {
        return resolveTtsModel() + "|" + resolveVoice(voiceIdOverride);
    }

    /** 管理端展示：固定话术预生成使用的 CosyVoice 模型 */
    public String effectiveTtsModel() {
        return resolveTtsModel();
    }

    /** 管理端展示：固定话术预生成使用的 CosyVoice 音色 */
    public String effectiveTtsVoice() {
        return resolveVoice(null);
    }

    public boolean usesSseStream() {
        return useSseStream();
    }

    /** v1 等旧模型不支持 SSE，走非流式 JSON + 音频 URL */
    private boolean useSseStream() {
        String model = resolveTtsModel().toLowerCase();
        return !model.contains("v1");
    }

    /**
     * 非流式：返回 output.audio.url，下载后转 8k 电话 wav。
     */
    private byte[] synthesizeAudioBytesSync(String apiKey, String text, String voiceIdOverride) throws Exception {
        Map<String, Object> body = buildTtsRequestBody(text, false, voiceIdOverride);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(TTS_URL))
                .timeout(Duration.ofSeconds(28))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        String respBody = response.body();
        if (response.statusCode() != 200) {
            if (response.statusCode() == 429 || response.statusCode() == 428
                    || (respBody != null && (respBody.contains("428") || respBody.contains("Throttling")))) {
                throw new IllegalStateException("CosyVoice rate limit: " + truncate(respBody, 200));
            }
            log.warn("CosyVoice 非流式失败 HTTP {}: {}", response.statusCode(), truncate(respBody, 300));
            return null;
        }
        JsonNode root = objectMapper.readTree(respBody);
        if (root.has("code") && StringUtils.hasText(root.path("code").asText())) {
            throw new IllegalStateException(root.path("message").asText("CosyVoice 非流式错误"));
        }
        String url = root.path("output").path("audio").path("url").asText("");
        String data = root.path("output").path("audio").path("data").asText("");
        if (StringUtils.hasText(data)) {
            return decodeBase64Audio(data);
        }
        if (!StringUtils.hasText(url)) {
            log.warn("CosyVoice 非流式无 audio.url/data");
            return null;
        }
        return downloadAudioUrl(url);
    }

    private byte[] downloadAudioUrl(String url) throws Exception {
        HttpRequest get = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(25))
                .GET()
                .build();
        HttpResponse<byte[]> resp = httpClient.send(get, HttpResponse.BodyHandlers.ofByteArray());
        if (resp.statusCode() != 200 || resp.body() == null || resp.body().length < 320) {
            log.warn("CosyVoice 下载音频失败 HTTP {} url={}", resp.statusCode(), truncate(url, 80));
            return null;
        }
        return resp.body();
    }

    /**
     * SSE 流式合成：Header {@code X-DashScope-SSE: enable}，逐段读取 {@code output.audio.data}。
     */
    private byte[] synthesizeAudioBytesSse(String apiKey, String text, String voiceIdOverride) throws Exception {
        Map<String, Object> body = buildTtsRequestBody(text, true, voiceIdOverride);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(TTS_URL))
                .timeout(Duration.ofSeconds(28))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .header("X-DashScope-SSE", "enable")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();

        HttpResponse<InputStream> response = httpClient.send(
                request, HttpResponse.BodyHandlers.ofInputStream());

        if (response.statusCode() != 200) {
            String err = readAll(response.body());
            if (response.statusCode() == 429 || response.statusCode() == 428
                    || (err != null && (err.contains("428") || err.contains("Throttling")))) {
                throw new IllegalStateException("CosyVoice rate limit: " + truncate(err, 200));
            }
            log.warn("CosyVoice SSE 失败 HTTP {}: {}", response.statusCode(), truncate(err, 300));
            return null;
        }

        ByteArrayOutputStream audio = new ByteArrayOutputStream();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data:")) {
                    continue;
                }
                String payload = line.substring(5).trim();
                if (!StringUtils.hasText(payload) || "[DONE]".equals(payload)) {
                    continue;
                }
                appendSseAudioChunk(audio, payload);
            }
        }
        return audio.size() > 0 ? audio.toByteArray() : null;
    }

    private Map<String, Object> buildTtsRequestBody(String text, boolean sse, String voiceIdOverride) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("text", trimText(text));
        input.put("voice", resolveVoice(voiceIdOverride));
        String model = resolveTtsModel().toLowerCase();
        int synthRate = resolveSynthSampleRate();
        if (sse || !model.contains("v1")) {
            input.put("format", "pcm");
            input.put("sample_rate", synthRate);
        } else {
            input.put("format", "wav");
            input.put("sample_rate", synthRate);
        }
        input.put("rate", aiVoiceProperties.getTtsSpeechRate());
        input.put("pitch", aiVoiceProperties.getTtsPitchRate());
        input.put("volume", aiVoiceProperties.getTtsVolume());
        if (!model.contains("v1") && StringUtils.hasText(aiVoiceProperties.getTtsInstruction())) {
            input.put("instruction", aiVoiceProperties.getTtsInstruction().trim());
        }
        Map<String, Object> parameters = new LinkedHashMap<>();
        if (sse) {
            parameters.put("response_format", "audio");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", resolveTtsModel());
        body.put("input", input);
        if (!parameters.isEmpty()) {
            body.put("parameters", parameters);
        }
        return body;
    }

    private void appendSseAudioChunk(ByteArrayOutputStream audio, String payload) throws Exception {
        JsonNode root = objectMapper.readTree(payload);
        if (root.has("code") && StringUtils.hasText(root.get("code").asText())) {
            throw new IllegalStateException(root.path("message").asText("CosyVoice SSE 错误"));
        }
        JsonNode audioNode = root.path("output").path("audio");
        if (audioNode.isMissingNode()) {
            return;
        }
        String url = audioNode.path("url").asText("");
        String data = audioNode.path("data").asText("");
        if (StringUtils.hasText(url) && !StringUtils.hasText(data)) {
            return;
        }
        if (!StringUtils.hasText(data)) {
            return;
        }
        byte[] chunk = decodeBase64Audio(data);
        if (chunk != null && chunk.length > 0) {
            audio.write(chunk);
        }
    }

    private byte[] toTelephonyWav(byte[] raw) throws Exception {
        if (raw.length >= 4 && raw[0] == 'R' && raw[1] == 'I' && raw[2] == 'F' && raw[3] == 'F') {
            return TelephonyWavUtil.toTelephony8kMono(raw);
        }
        int synthRate = resolveSynthSampleRate();
        byte[] wav = TelephonyWavUtil.buildWavFromRawPcm(raw, synthRate, 1, 16);
        return synthRate == TelephonyWavUtil.TELEPHONY_RATE
                ? wav : TelephonyWavUtil.toTelephony8kMono(wav);
    }

    private int resolveSynthSampleRate() {
        int rate = aiVoiceProperties.getTtsSampleRate();
        return rate > 0 ? rate : TelephonyWavUtil.TELEPHONY_RATE;
    }

    private byte[] decodeBase64Audio(String audioField) {
        if (!StringUtils.hasText(audioField)) {
            return null;
        }
        String b64 = audioField.trim();
        int idx = b64.indexOf("base64,");
        if (idx >= 0) {
            b64 = b64.substring(idx + 7);
        }
        try {
            return Base64.getDecoder().decode(b64);
        } catch (Exception e) {
            log.debug("Base64 音频解码失败: {}", e.getMessage());
            return null;
        }
    }

    private String resolveTtsModel() {
        String model = aiVoiceProperties.getTtsModel();
        if (!StringUtils.hasText(model)) {
            return "cosyvoice-v2";
        }
        return model.trim();
    }

    /** v2/v3/v3.5 模型须用对应版本音色（v3.5 仅声音复刻/设计 ID） */
    private String resolveVoice(String voiceIdOverride) {
        if (StringUtils.hasText(voiceIdOverride)) {
            return voiceIdOverride.trim();
        }
        String voice = aiVoiceProperties.getTtsVoice();
        String model = resolveTtsModel().toLowerCase();
        if (model.contains("v3.5")) {
            String cloneId = voiceRuntimeSettingsService.getCosyvoiceCloneVoiceId();
            if (StringUtils.hasText(cloneId)) {
                return cloneId.trim();
            }
            if (StringUtils.hasText(voice) && !isSystemVoiceName(voice)) {
                return voice.trim();
            }
            log.warn("cosyvoice-v3.5 未配置 tts-clone-voice-id，TTS 可能失败（v3.5 不支持 longanyang 等系统音色）");
            return StringUtils.hasText(voice) ? voice.trim() : "";
        }
        if (!StringUtils.hasText(voice)) {
            if (model.contains("v3.5")) {
                return "";
            }
            if (model.contains("v3")) {
                return "longanyang";
            }
            if (model.contains("v1")) {
                return "longxiaochun";
            }
            return "longxiaochun_v2";
        }
        voice = voice.trim();
        if (model.contains("v1")) {
            if ("longxiaochun_v2".equals(voice) || voice.endsWith("_v2")) {
                return "longxiaochun";
            }
            return voice;
        }
        if (model.contains("v3")) {
            if (isSystemVoiceName(voice) && voice.endsWith("_v2")) {
                return "longanyang";
            }
            if ("longxiaochun".equals(voice) || "longwan".equals(voice) || "longwan_v2".equals(voice)) {
                return "longanyang";
            }
            return voice;
        }
        if (model.contains("v2") && "longxiaochun".equals(voice)) {
            return "longxiaochun_v2";
        }
        return voice;
    }

    private static boolean isSystemVoiceName(String voice) {
        if (!StringUtils.hasText(voice)) {
            return false;
        }
        String v = voice.trim().toLowerCase();
        return v.startsWith("long") && !v.matches(".*[0-9a-f]{8,}.*");
    }

    private String trimText(String text) {
        // 开场白/结束语预合成须全文；对话轮次字数在 CallAiVoiceService / OllamaChatService 已截断
        return text != null ? text.trim() : "";
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) {
            return "";
        }
        try (in) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
