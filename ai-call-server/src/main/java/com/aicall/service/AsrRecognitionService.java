package com.aicall.service;

import com.aicall.config.AiVoiceProperties;
import com.aicall.dto.AsrBrowserResult;
import com.aicall.util.AsrHintEchoFilter;
import com.aicall.util.AsrPhantomFilter;
import com.aicall.util.AsrFillerFilter;
import com.aicall.util.AsrTextNormalizer;
import com.aicall.util.TelephonyVadUtil;
import com.aicall.util.WavPcmUtil;
import com.aicall.util.TelephonyAsrAudioUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 语音识别：8kHz/mono/16bit PCM wav；优先 HTTP ASR（FunASR 等），否则 DashScope 电话 8k 模型。
 */
@Slf4j
@Service
public class AsrRecognitionService {

    private static final String DASHSCOPE_ASR_GEN =
            "https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation";
    private static final String DASHSCOPE_FILE_ASR =
            "https://dashscope.aliyuncs.com/api/v1/services/audio/asr/transcription";

    private final AiVoiceProperties aiVoiceProperties;
    private final DashScopeApiKeyResolver dashScopeApiKeyResolver;
    private final TelephonyAsrAudioUtil telephonyAsrAudioUtil;
    private final ParaformerRealtimeAsrService paraformerRealtimeAsrService;
    private final RestTemplate asrRestTemplate;
    private final ObjectMapper objectMapper;

    public AsrRecognitionService(AiVoiceProperties aiVoiceProperties,
                                 DashScopeApiKeyResolver dashScopeApiKeyResolver,
                                 TelephonyAsrAudioUtil telephonyAsrAudioUtil,
                                 ParaformerRealtimeAsrService paraformerRealtimeAsrService,
                                 @Qualifier("asrRestTemplate") RestTemplate asrRestTemplate,
                                 ObjectMapper objectMapper) {
        this.aiVoiceProperties = aiVoiceProperties;
        this.dashScopeApiKeyResolver = dashScopeApiKeyResolver;
        this.telephonyAsrAudioUtil = telephonyAsrAudioUtil;
        this.paraformerRealtimeAsrService = paraformerRealtimeAsrService;
        this.asrRestTemplate = asrRestTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 管理端浏览器麦克风：16k wav、不传 asr-context-hint。
     * 不做 VAD 硬拦截（浏览器 webm 经 ffmpeg 后能量常偏低），低音量自动增益。
     */
    public String recognizeBrowserMic(Path wavFile) {
        return recognizeBrowserMicResult(wavFile).text();
    }

    /** 浏览器训练 ASR，带失败原因码 */
    public AsrBrowserResult recognizeBrowserMicResult(Path wavFile) {
        if (wavFile == null || !Files.exists(wavFile)) {
            return AsrBrowserResult.fail("file_missing");
        }
        try {
            long size = Files.size(wavFile);
            if (size <= 44) {
                return AsrBrowserResult.fail("file_empty");
            }
            WavPcmUtil.MonoPcm pcm;
            try {
                pcm = WavPcmUtil.readMono(wavFile);
            } catch (Exception e) {
                log.warn("[ASR] 浏览器训练 wav 解析失败: {}", e.getMessage());
                return AsrBrowserResult.fail("wav_parse_error");
            }
            if (pcm.durationMs() < 400) {
                log.warn("[ASR] 浏览器训练：录音过短 durationMs={} bytes={}", pcm.durationMs(), size);
                return AsrBrowserResult.fail("too_short");
            }
            amplifyBrowserMicIfNeeded(wavFile);
            pcm = WavPcmUtil.readMono(wavFile);
            int peak = WavPcmUtil.peakRms(pcm.samples());
            if (peak < 50) {
                amplifyBrowserMicIfNeeded(wavFile);
                pcm = WavPcmUtil.readMono(wavFile);
                peak = WavPcmUtil.peakRms(pcm.samples());
            }
            if (peak < 40) {
                log.warn("[ASR] 浏览器训练：录音几乎无声 peak={} durationMs={} bytes={}",
                        peak, pcm.durationMs(), size);
                return AsrBrowserResult.fail("silent");
            }

            String apiKey = dashScopeApiKeyResolver.resolve();
            if (!StringUtils.hasText(apiKey)) {
                log.warn("[ASR] 浏览器训练识别跳过：未配置 dashscope.api-key");
                return AsrBrowserResult.fail("asr_not_configured");
            }

            String text = recognizeViaQwenAsrFlash(wavFile, apiKey, "qwen3-asr-flash", "", 16000);
            text = normalizeBrowserAsrText(text, pcm, peak);
            if (StringUtils.hasText(text)) {
                log.info("[ASR] 浏览器训练 qwen 识别 len={} durationMs={} peak={}",
                        text.length(), pcm.durationMs(), peak);
                return AsrBrowserResult.ok(text);
            }

            Path telephony = Files.createTempFile("asr-browser-fallback-", ".wav");
            try {
                Files.copy(wavFile, telephony, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                telephonyAsrAudioUtil.prepareForAsr(telephony);
                text = recognizeViaQwenAsrFlash(telephony, apiKey, "qwen3-asr-flash", "", 8000);
                text = normalizeBrowserAsrText(text, pcm, peak);
                if (StringUtils.hasText(text)) {
                    log.info("[ASR] 浏览器训练 8k qwen 兜底成功 len={}", text.length());
                    return AsrBrowserResult.ok(text);
                }
            } finally {
                Files.deleteIfExists(telephony);
            }
            log.warn("[ASR] 浏览器训练识别为空 durationMs={} peak={} bytes={}", pcm.durationMs(), peak, size);
            return AsrBrowserResult.fail("no_text");
        } catch (Exception e) {
            log.warn("[ASR] 浏览器训练识别失败 file={}: {}", wavFile, e.getMessage());
            return AsrBrowserResult.fail("asr_error");
        }
    }

    private void amplifyBrowserMicIfNeeded(Path wavFile) throws IOException {
        WavPcmUtil.MonoPcm pcm = WavPcmUtil.readMono(wavFile);
        int peak = WavPcmUtil.peakRms(pcm.samples());
        if (peak >= 800) {
            return;
        }
        double gain = peak < 30 ? 20.0 : (peak < 80 ? 12.0 : (peak < 150 ? 8.0 : (peak < 400 ? 4.0 : 2.0)));
        WavPcmUtil.amplifyFile(wavFile, gain);
        int after = WavPcmUtil.peakRms(WavPcmUtil.readMono(wavFile).samples());
        log.info("[ASR] 浏览器训练增益 peak {}→{} gain={}x durationMs={}",
                peak, after, gain, pcm.durationMs());
    }

    private String normalizeBrowserAsrText(String text, WavPcmUtil.MonoPcm pcm, int peak) {
        text = AsrTextNormalizer.normalize(text != null ? text.trim() : "");
        if (!StringUtils.hasText(text)) {
            return "";
        }
        if (AsrFillerFilter.isValidShortAnswer(text)) {
            return text;
        }
        int fillerThreshold = peak >= 400 ? 120 : 200;
        if (AsrFillerFilter.isLikelyHallucinatedFiller(text, pcm, fillerThreshold)) {
            log.warn("[ASR] 浏览器训练丢弃疑似幻听/短答误识「{}」 durationMs={} peak={}",
                    text, pcm.durationMs(), peak);
            return "";
        }
        return text;
    }

    public String recognize(Path wavFile) {
        if (wavFile == null || !Files.exists(wavFile)) {
            return "";
        }
        try {
            Path prepared = telephonyAsrAudioUtil.prepareForAsr(wavFile);
            long size = Files.size(prepared);
            if (size <= 44) {
                return "";
            }
            WavPcmUtil.MonoPcm pcm = null;
            int peak = 0;
            try {
                pcm = WavPcmUtil.readMono(prepared);
                peak = WavPcmUtil.peakRms(pcm.samples());
                log.info("[ASR] 电话音频 peak={} durationMs={} file={}", peak, pcm.durationMs(), prepared.getFileName());
            } catch (Exception e) {
                log.debug("[ASR] 电话 wav 解析跳过 filler 过滤: {}", e.getMessage());
            }
            String text = "";
            if (StringUtils.hasText(aiVoiceProperties.getAsrHttpUrl())) {
                text = finalizeAsrText(recognizeViaHttp(prepared), pcm);
            }
            if (!StringUtils.hasText(text)) {
                String apiKey = dashScopeApiKeyResolver.resolve();
                if (StringUtils.hasText(apiKey)) {
                    text = finalizeAsrText(recognizeViaDashScopeTelephony(prepared, apiKey), pcm);
                }
            }
            if (StringUtils.hasText(text)) {
                return text;
            }
            log.warn("ASR 结果为空 file={} bytes={}（请检查录音是否含人声、dashscope.api-key）",
                    prepared.getFileName(), size);
            return "";
        } catch (Exception e) {
            log.warn("ASR 识别失败 file={}: {}", wavFile, e.getMessage());
            return "";
        }
    }

    private String recognizeViaHttp(Path wavFile) {
        String baseUrl = aiVoiceProperties.getAsrHttpUrl().trim();
        String url = appendAsrQueryParams(baseUrl);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new FileSystemResource(wavFile.toFile()));
        body.add("audio", new FileSystemResource(wavFile.toFile()));
        body.add("wav", new FileSystemResource(wavFile.toFile()));
        body.add("language", aiVoiceProperties.getAsrLanguage());
        body.add("model", aiVoiceProperties.getAsrModel());
        body.add("vad_silence_time", String.valueOf(aiVoiceProperties.resolveAsrVadSilenceMs()));
        body.add("sample_rate", "8000");
        body.add("channels", "1");
        body.add("format", aiVoiceProperties.getAsrAudioFormat());
        body.add("bit_depth", "16");
        try {
            ResponseEntity<String> resp = asrRestTemplate.exchange(
                    url, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
            if (!resp.getStatusCode().is2xxSuccessful() || resp.getBody() == null) {
                log.warn("ASR HTTP 失败 status={}", resp.getStatusCode());
                return "";
            }
            return extractTextFromJson(resp.getBody());
        } catch (Exception e) {
            log.warn("ASR HTTP 调用异常 url={}: {}", url, e.getMessage());
            return "";
        }
    }

    private String appendAsrQueryParams(String baseUrl) {
        if (baseUrl.contains("language=") || baseUrl.contains("vad_silence_time=")) {
            return baseUrl;
        }
        return UriComponentsBuilder.fromHttpUrl(baseUrl)
                .queryParam("language", aiVoiceProperties.getAsrLanguage())
                .queryParam("model", aiVoiceProperties.getAsrModel())
                .queryParam("vad_silence_time", aiVoiceProperties.resolveAsrVadSilenceMs())
                .queryParam("sample_rate", 8000)
                .queryParam("channels", 1)
                .queryParam("format", aiVoiceProperties.getAsrAudioFormat())
                .build()
                .toUriString();
    }

    private String resolveDashScopeModel() {
        String configured = aiVoiceProperties.getAsrDashscopeModel();
        if (StringUtils.hasText(configured) && !"phone_call".equalsIgnoreCase(configured)) {
            return configured.trim();
        }
        if ("phone_call".equalsIgnoreCase(aiVoiceProperties.getAsrModel())) {
            return "paraformer-v1";
        }
        return StringUtils.hasText(configured) ? configured : "paraformer-v1";
    }

    private boolean useQwenMultimodalAsr(String model) {
        if (!StringUtils.hasText(model)) {
            return true;
        }
        String m = model.toLowerCase();
        return m.contains("qwen") || m.contains("ultra") || m.contains("flash");
    }

    private String recognizeViaDashScopeTelephony(Path wavFile, String apiKey) throws Exception {
        String model = resolveDashScopeModel();
        if (!useQwenMultimodalAsr(model)) {
            if (aiVoiceProperties.isAsrParaformerRealtimeEnabled()) {
                String realtimeModel = resolveRealtimeParaformerModel(model);
                String viaWs = paraformerRealtimeAsrService.recognize(
                        wavFile, apiKey, realtimeModel, aiVoiceProperties.getAsrRealtimeTimeoutSec());
                if (StringUtils.hasText(viaWs)) {
                    return viaWs;
                }
            }
            String viaFile = tryParaformerFileTranscription(wavFile, apiKey, model);
            if (StringUtils.hasText(viaFile)) {
                return viaFile;
            }
            log.warn("[ASR] Paraformer 识别无结果 model={}，尝试 qwen3-asr-flash 兜底", model);
            return recognizeViaQwenAsrFlash(wavFile, apiKey, "qwen3-asr-flash", "", null);
        }
        return recognizeViaQwenAsrFlash(wavFile, apiKey, model, "", null);
    }

    private String resolveRealtimeParaformerModel(String configured) {
        if (!StringUtils.hasText(configured)) {
            return "paraformer-realtime-8k-v2";
        }
        String m = configured.trim().toLowerCase();
        if (m.contains("realtime")) {
            return configured.trim();
        }
        if (m.contains("8k")) {
            return "paraformer-realtime-8k-v2";
        }
        return "paraformer-realtime-v2";
    }

    /** 支持 data:audio/wav;base64 直传的 DashScope 模型（电话 8k 场景） */
    private String resolveInlineBase64AsrModel(String configured) {
        if (useQwenMultimodalAsr(configured)) {
            return configured.trim();
        }
        return "qwen3-asr-flash";
    }

    /** 8k 电话文件转写（需 file_urls 公网可达；本地开发常走 qwen 兜底） */
    private String tryParaformerFileTranscription(Path wavFile, String apiKey, String model) {
        String playback = aiVoiceProperties.getPlaybackBaseUrl();
        if (!StringUtils.hasText(playback)
                || playback.contains("127.0.0.1")
                || playback.contains("localhost")) {
            log.debug("DashScope 文件转写跳过：playback-base-url 非公网");
            return "";
        }
        try {
            Path published = Path.of("./uploads/asr", wavFile.getFileName().toString());
            Files.createDirectories(published.getParent());
            Files.copy(wavFile, published, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            String base = playback.endsWith("/") ? playback.substring(0, playback.length() - 1) : playback;
            String fileUrl = base + "/uploads/asr/" + published.getFileName();

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(apiKey);
            headers.set("X-DashScope-Async", "enable");

            Map<String, Object> parameters = new LinkedHashMap<>();
            parameters.put("language_hints", List.of("zh"));

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            body.put("input", Map.of("file_urls", List.of(fileUrl)));
            body.put("parameters", parameters);

            ResponseEntity<String> submit = asrRestTemplate.exchange(
                    DASHSCOPE_FILE_ASR, HttpMethod.POST,
                    new HttpEntity<>(objectMapper.writeValueAsString(body), headers), String.class);
            if (!submit.getStatusCode().is2xxSuccessful() || submit.getBody() == null) {
                return "";
            }
            JsonNode root = objectMapper.readTree(submit.getBody());
            String taskId = root.path("output").path("task_id").asText("");
            if (!StringUtils.hasText(taskId)) {
                return "";
            }
            return pollTranscriptionTask(taskId, apiKey);
        } catch (Exception e) {
            log.debug("DashScope paraformer 文件转写失败: {}", e.getMessage());
            return "";
        }
    }

    private String pollTranscriptionTask(String taskId, String apiKey) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(apiKey);
        String queryUrl = "https://dashscope.aliyuncs.com/api/v1/tasks/" + taskId;
        for (int i = 0; i < 30; i++) {
            Thread.sleep(500L);
            ResponseEntity<String> resp = asrRestTemplate.exchange(
                    queryUrl, HttpMethod.GET, new HttpEntity<>(headers), String.class);
            if (resp.getBody() == null) {
                continue;
            }
            JsonNode root = objectMapper.readTree(resp.getBody());
            String status = root.path("output").path("task_status").asText("");
            if ("SUCCEEDED".equalsIgnoreCase(status)) {
                JsonNode results = root.path("output").path("results");
                if (results.isArray() && !results.isEmpty()) {
                    String transcriptionUrl = results.get(0).path("transcription_url").asText("");
                    if (StringUtils.hasText(transcriptionUrl)) {
                        return fetchTranscriptionText(transcriptionUrl);
                    }
                }
                return extractTextFromJson(resp.getBody());
            }
            if ("FAILED".equalsIgnoreCase(status)) {
                break;
            }
        }
        return "";
    }

    private String fetchTranscriptionText(String url) {
        try {
            ResponseEntity<String> resp = asrRestTemplate.getForEntity(url, String.class);
            return extractTextFromJson(resp.getBody());
        } catch (Exception e) {
            return "";
        }
    }

    private String recognizeViaQwenAsrFlash(Path wavFile, String apiKey, String model) throws Exception {
        return recognizeViaQwenAsrFlash(wavFile, apiKey, model, null, null);
    }

    /**
     * @param contextHint null=用配置 hint；空串=不传 hint（浏览器训练）
     * @param sampleRate  null=8000（电话）
     */
    private String recognizeViaQwenAsrFlash(Path wavFile, String apiKey, String model,
            String contextHint, Integer sampleRate) throws Exception {
        int maxAttempts = 3;
        Exception last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return recognizeViaQwenAsrFlashOnce(wavFile, apiKey, model, contextHint, sampleRate);
            } catch (Exception e) {
                last = e;
                if (attempt < maxAttempts && isRetryableAsrError(e)) {
                    log.warn("[ASR] qwen 识别网络异常，重试 {}/{}: {}", attempt, maxAttempts, e.getMessage());
                    Thread.sleep(400L * attempt);
                } else {
                    throw e;
                }
            }
        }
        throw last != null ? last : new IOException("ASR 重试失败");
    }

    private static boolean isRetryableAsrError(Throwable e) {
        String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
        return msg.contains("connection reset") || msg.contains("connection refused")
                || msg.contains("timed out") || msg.contains("timeout")
                || msg.contains("broken pipe") || msg.contains("goaway")
                || msg.contains("503") || msg.contains("502");
    }

    private String recognizeViaQwenAsrFlashOnce(Path wavFile, String apiKey, String model,
            String contextHint, Integer sampleRate) throws Exception {
        long start = System.currentTimeMillis();
        byte[] wav = Files.readAllBytes(wavFile);
        String audioDataUrl = "data:audio/wav;base64," + Base64.getEncoder().encodeToString(wav);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);

        int rate = sampleRate != null ? sampleRate : 8000;
        Map<String, Object> asrOptions = new LinkedHashMap<>();
        asrOptions.put("enable_itn", aiVoiceProperties.isAsrEnableItn());
        asrOptions.put("language", mapLanguage(aiVoiceProperties.getAsrLanguage()));
        asrOptions.put("sample_rate", rate);
        asrOptions.put("max_sentence_silence", aiVoiceProperties.resolveAsrVadSilenceMs());

        String asrModel = resolveInlineBase64AsrModel(model);
        String asrContext;
        if (contextHint != null) {
            asrContext = contextHint;
        } else {
            asrContext = StringUtils.hasText(aiVoiceProperties.getAsrContextHint())
                    ? aiVoiceProperties.getAsrContextHint().trim() : "";
        }
        List<Map<String, Object>> messages = new java.util.ArrayList<>(2);
        if (StringUtils.hasText(asrContext)) {
            messages.add(Map.of("role", "system", "content", List.of(Map.of("text", asrContext))));
        }
        messages.add(Map.of("role", "user", "content", List.of(Map.of("audio", audioDataUrl))));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", asrModel);
        body.put("input", Map.of("messages", messages));
        body.put("parameters", Map.of("asr_options", asrOptions));

        ResponseEntity<String> resp = asrRestTemplate.exchange(
                DASHSCOPE_ASR_GEN, HttpMethod.POST,
                new HttpEntity<>(objectMapper.writeValueAsString(body), headers), String.class);
        if (!resp.getStatusCode().is2xxSuccessful() || resp.getBody() == null) {
            log.warn("DashScope ASR 失败 model={} status={} body={}",
                    asrModel, resp.getStatusCode(),
                    resp.getBody() != null && resp.getBody().length() > 200
                            ? resp.getBody().substring(0, 200) + "…" : resp.getBody());
            return "";
        }
            String text = extractTextFromJson(resp.getBody());
            if (StringUtils.hasText(text)) {
                log.info("[ASR] DashScope model={} 耗时={}ms len={}",
                        asrModel, System.currentTimeMillis() - start, text.length());
            }
            return text;
    }

    private static String mapLanguage(String lang) {
        if (!StringUtils.hasText(lang)) {
            return "zh";
        }
        if (lang.toLowerCase().startsWith("zh")) {
            return "zh";
        }
        return lang;
    }

    private String extractTextFromJson(String raw) {
        if (!StringUtils.hasText(raw)) {
            return "";
        }
        try {
            JsonNode root = objectMapper.readTree(raw);
            String[] paths = {"output.choices.0.message.content.0.text", "output.text", "text", "result"};
            for (String p : paths) {
                String v = readJsonPath(root, p);
                if (StringUtils.hasText(v)) {
                    return v.trim();
                }
            }
            if (root.has("output") && root.get("output").has("choices")) {
                JsonNode choices = root.get("output").get("choices");
                if (choices.isArray() && !choices.isEmpty()) {
                    JsonNode content = choices.get(0).path("message").path("content");
                    if (content.isArray()) {
                        for (JsonNode item : content) {
                            if (item.has("text") && StringUtils.hasText(item.get("text").asText())) {
                                return item.get("text").asText().trim();
                            }
                        }
                    }
                }
            }
            if (root.has("transcripts") && root.get("transcripts").isArray() && !root.get("transcripts").isEmpty()) {
                return root.get("transcripts").get(0).path("text").asText("").trim();
            }
            if (root.has("results") && root.get("results").isArray() && !root.get("results").isEmpty()) {
                JsonNode first = root.get("results").get(0);
                if (first.has("text")) {
                    return first.get("text").asText("").trim();
                }
            }
        } catch (Exception e) {
            log.debug("ASR JSON 解析: {}", e.getMessage());
        }
        return raw.trim().length() < 200 ? raw.trim() : "";
    }

    private String readJsonPath(JsonNode root, String path) {
        JsonNode cur = root;
        for (String part : path.split("\\.")) {
            if (cur == null) {
                return "";
            }
            if (part.matches("\\d+")) {
                int idx = Integer.parseInt(part);
                if (!cur.isArray() || idx >= cur.size()) {
                    return "";
                }
                cur = cur.get(idx);
            } else if (!cur.has(part)) {
                return "";
            } else {
                cur = cur.get(part);
            }
        }
        return cur != null && cur.isTextual() ? cur.asText() : "";
    }

    private String finalizeAsrText(String text) {
        return finalizeAsrText(text, null);
    }

    private String finalizeAsrText(String text, WavPcmUtil.MonoPcm pcm) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        String trimmed = text.trim();
        trimmed = AsrTextNormalizer.normalize(trimmed);
        String hint = aiVoiceProperties.getAsrContextHint();
        if (AsrHintEchoFilter.isEcho(trimmed, hint)) {
            String preview = trimmed.length() > 60 ? trimmed.substring(0, 60) + "…" : trimmed;
            log.warn("[ASR] 丢弃 context-hint 回声 text={}", preview);
            return "";
        }
        if (AsrPhantomFilter.isSystemPhantom(trimmed)) {
            log.warn("[ASR] 丢弃系统提示语幻听 text={}", trimmed);
            return "";
        }
        if (pcm != null && !AsrFillerFilter.isValidShortAnswer(trimmed)
                && (AsrFillerFilter.isLikelyHallucinatedFiller(trimmed, pcm, 120)
                || AsrFillerFilter.isLikelyShortMisrecognition(trimmed, pcm))) {
            log.warn("[ASR] 电话丢弃疑似幻听/短答误识「{}」 durationMs={}", trimmed, pcm.durationMs());
            return "";
        }
        return trimmed;
    }
}
