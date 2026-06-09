package com.aicall.service;

import com.aicall.common.BizException;
import com.aicall.config.AiVoiceProperties;
import com.aicall.dto.CosyVoiceVoiceEnrollRequest;
import com.aicall.dto.CosyVoiceVoiceItemDto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * DashScope CosyVoice 声音复刻（voice-enrollment API）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CosyVoiceVoiceEnrollmentService {

    private static final String CUSTOMIZATION_URL =
            "https://dashscope.aliyuncs.com/api/v1/services/audio/tts/customization";
    private static final Pattern PREFIX_PATTERN = Pattern.compile("^[a-zA-Z0-9]{1,10}$");

    private final AiVoiceProperties aiVoiceProperties;
    private final DashScopeApiKeyResolver dashScopeApiKeyResolver;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    public List<CosyVoiceVoiceItemDto> listVoices() {
        String apiKey = requireApiKey();
        String targetModel = resolveTargetModel();
        List<CosyVoiceVoiceItemDto> all = new ArrayList<>();
        int pageSize = 50;
        for (int page = 0; page < 10; page++) {
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("action", "list_voice");
            input.put("page_index", page);
            input.put("page_size", pageSize);
            JsonNode output = invoke(apiKey, input);
            JsonNode list = output.path("voice_list");
            if (!list.isArray() || list.isEmpty()) {
                break;
            }
            for (JsonNode item : list) {
                CosyVoiceVoiceItemDto dto = new CosyVoiceVoiceItemDto();
                String voiceId = item.path("voice_id").asText("");
                dto.setVoiceId(voiceId);
                dto.setStatus(item.path("status").asText(""));
                dto.setGmtCreate(item.path("gmt_create").asText(""));
                dto.setCompatible(isCompatibleVoice(voiceId, targetModel));
                all.add(dto);
            }
            if (list.size() < pageSize) {
                break;
            }
        }
        log.info("[CosyVoice复刻] 查询音色 {} 条，当前模型 target={}", all.size(), targetModel);
        return all;
    }

    public CosyVoiceVoiceItemDto enroll(CosyVoiceVoiceEnrollRequest req) {
        if (req == null) {
            throw new BizException("请求不能为空");
        }
        String prefix = req.getPrefix() != null ? req.getPrefix().trim() : "";
        String audioUrl = req.getAudioUrl() != null ? req.getAudioUrl().trim() : "";
        if (!PREFIX_PATTERN.matcher(prefix).matches()) {
            throw new BizException("音色前缀仅允许字母数字，1~10 位");
        }
        if (!StringUtils.hasText(audioUrl) || !audioUrl.startsWith("http")) {
            throw new BizException("请填写可公网访问的音频 URL（http/https）");
        }
        String apiKey = requireApiKey();
        String targetModel = resolveTargetModel();
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("action", "create_voice");
        input.put("target_model", targetModel);
        input.put("prefix", prefix);
        input.put("url", audioUrl);
        String lang = StringUtils.hasText(req.getLanguageHint()) ? req.getLanguageHint().trim() : "zh";
        input.put("language_hints", List.of(lang));

        JsonNode output = invoke(apiKey, input);
        String voiceId = output.path("voice_id").asText("");
        if (!StringUtils.hasText(voiceId)) {
            throw new BizException("复刻成功但未返回 voice_id，请查看服务端日志");
        }
        log.info("[CosyVoice复刻] 创建成功 voiceId={} target={}", voiceId, targetModel);
        CosyVoiceVoiceItemDto dto = new CosyVoiceVoiceItemDto();
        dto.setVoiceId(voiceId);
        dto.setStatus("OK");
        dto.setCompatible(true);
        return dto;
    }

    public void deleteVoice(String voiceId) {
        if (!StringUtils.hasText(voiceId)) {
            throw new BizException("voice_id 不能为空");
        }
        String apiKey = requireApiKey();
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("action", "delete_voice");
        input.put("voice_id", voiceId.trim());
        invoke(apiKey, input);
        log.info("[CosyVoice复刻] 已删除 voiceId={}", voiceId.trim());
    }

    public String resolveTargetModel() {
        String model = aiVoiceProperties.getTtsModel();
        return StringUtils.hasText(model) ? model.trim() : "cosyvoice-v3.5-plus";
    }

    public boolean isCompatibleVoice(String voiceId, String targetModel) {
        if (!StringUtils.hasText(voiceId)) {
            return false;
        }
        String v = voiceId.trim().toLowerCase();
        String target = (targetModel != null ? targetModel : resolveTargetModel()).trim().toLowerCase();
        return v.startsWith(target + "-") || v.startsWith(target);
    }

    private String requireApiKey() {
        if (!dashScopeApiKeyResolver.isConfigured()) {
            throw new BizException("请先配置 DashScope API Key（模型配置 → 通义千问）");
        }
        return dashScopeApiKeyResolver.requireOrThrow("CosyVoice 声音复刻");
    }

    private JsonNode invoke(String apiKey, Map<String, Object> input) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", "voice-enrollment");
            body.put("input", input);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(CUSTOMIZATION_URL))
                    .timeout(Duration.ofSeconds(60))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            String respBody = response.body();
            if (response.statusCode() != 200) {
                log.warn("[CosyVoice复刻] HTTP {}: {}", response.statusCode(), truncate(respBody, 400));
                throw new BizException(parseErrorMessage(respBody, response.statusCode()));
            }
            JsonNode root = objectMapper.readTree(respBody);
            if (root.has("code") && StringUtils.hasText(root.path("code").asText())) {
                throw new BizException(root.path("message").asText("CosyVoice 复刻失败"));
            }
            return root.path("output");
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.warn("[CosyVoice复刻] 调用异常: {}", e.getMessage());
            throw new BizException("CosyVoice 复刻请求失败: " + e.getMessage());
        }
    }

    private static String parseErrorMessage(String body, int status) {
        if (!StringUtils.hasText(body)) {
            return "CosyVoice 复刻失败 HTTP " + status;
        }
        try {
            JsonNode root = new ObjectMapper().readTree(body);
            String msg = root.path("message").asText("");
            if (StringUtils.hasText(msg)) {
                return msg;
            }
        } catch (Exception ignored) {
            /* use raw */
        }
        return truncate(body, 200);
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
