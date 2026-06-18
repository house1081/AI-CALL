package com.aicall.service;

import com.aicall.config.DialogRagProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** DashScope 文本嵌入（对话训练 RAG 向量） */
@Slf4j
@Service
public class DashScopeEmbeddingService {

    private static final String EMBED_URL =
            "https://dashscope.aliyuncs.com/api/v1/services/embeddings/text-embedding/text-embedding";

    private final DialogRagProperties dialogRagProperties;
    private final DashScopeApiKeyResolver dashScopeApiKeyResolver;
    private final RestTemplate llmRestTemplate;
    private final ObjectMapper objectMapper;
    private final EmbeddingCacheService embeddingCacheService;

    public DashScopeEmbeddingService(DialogRagProperties dialogRagProperties,
                                     DashScopeApiKeyResolver dashScopeApiKeyResolver,
                                     @Qualifier("llmRestTemplate") RestTemplate llmRestTemplate,
                                     ObjectMapper objectMapper,
                                     EmbeddingCacheService embeddingCacheService) {
        this.dialogRagProperties = dialogRagProperties;
        this.dashScopeApiKeyResolver = dashScopeApiKeyResolver;
        this.llmRestTemplate = llmRestTemplate;
        this.objectMapper = objectMapper;
        this.embeddingCacheService = embeddingCacheService;
    }

    public float[] embed(String text) {
        if (!StringUtils.hasText(text)) {
            return new float[0];
        }
        float[] cached = embeddingCacheService.get(text);
        if (cached != null && cached.length > 0) {
            return cached;
        }
        float[] vec = embedRemote(text);
        if (vec.length > 0) {
            embeddingCacheService.put(text, vec);
        }
        return vec;
    }

    private float[] embedRemote(String text) {
        String apiKey = dashScopeApiKeyResolver.resolve();
        if (!StringUtils.hasText(apiKey)) {
            log.warn("[RAG嵌入] 未配置 DashScope API Key，跳过嵌入");
            return new float[0];
        }
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(apiKey);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", dialogRagProperties.getEmbeddingModel());
            body.put("input", Map.of("texts", List.of(text.trim())));
            ResponseEntity<String> resp = llmRestTemplate.exchange(
                    EMBED_URL, HttpMethod.POST,
                    new HttpEntity<>(objectMapper.writeValueAsString(body), headers), String.class);
            if (!resp.getStatusCode().is2xxSuccessful() || resp.getBody() == null) {
                log.warn("[RAG嵌入] HTTP 失败 status={}", resp.getStatusCode());
                return new float[0];
            }
            JsonNode root = objectMapper.readTree(resp.getBody());
            JsonNode emb = root.path("output").path("embeddings");
            if (!emb.isArray() || emb.isEmpty()) {
                return new float[0];
            }
            JsonNode vec = emb.get(0).path("embedding");
            if (!vec.isArray()) {
                return new float[0];
            }
            float[] out = new float[vec.size()];
            for (int i = 0; i < vec.size(); i++) {
                out[i] = (float) vec.get(i).asDouble();
            }
            return out;
        } catch (Exception e) {
            log.warn("[RAG嵌入] 失败: {}", e.getMessage());
            return new float[0];
        }
    }
}
