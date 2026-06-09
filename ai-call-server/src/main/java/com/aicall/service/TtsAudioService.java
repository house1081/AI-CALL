package com.aicall.service;

import com.aicall.config.AiVoiceProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

/**
 * 可选 HTTP TTS：合成 wav 落盘，由 FreeSWITCH 通过 HTTP playback 播放（适合中文 TTS 服务）。
 */
@Slf4j
@Service
public class TtsAudioService {

    private final AiVoiceProperties aiVoiceProperties;
    private final RestTemplate restTemplate;

    public TtsAudioService(AiVoiceProperties aiVoiceProperties,
                           @Qualifier("llmRestTemplate") RestTemplate restTemplate) {
        this.aiVoiceProperties = aiVoiceProperties;
        this.restTemplate = restTemplate;
    }

    /**
     * @return FS 可拉取的 playback URL；失败返回 null
     */
    public String synthesizePlaybackUrl(String fsUuid, String text) {
        if (!StringUtils.hasText(aiVoiceProperties.getTtsHttpUrl())
                || !StringUtils.hasText(text)) {
            return null;
        }
        try {
            Path dir = Paths.get("./uploads/tts");
            Files.createDirectories(dir);
            String fileName = (StringUtils.hasText(fsUuid) ? fsUuid : UUID.randomUUID().toString())
                    + "_" + System.currentTimeMillis() + ".wav";
            Path out = dir.resolve(fileName);

            String url = aiVoiceProperties.getTtsHttpUrl()
                    .replace("{text}", UriUtils.encode(text, StandardCharsets.UTF_8))
                    .replace("{uuid}", fsUuid != null ? fsUuid : "");

            HttpHeaders headers = new HttpHeaders();
            HttpEntity<Void> entity = new HttpEntity<>(headers);
            ResponseEntity<byte[]> resp = restTemplate.exchange(url, HttpMethod.GET, entity, byte[].class);
            byte[] body = resp.getBody();
            if (body == null || body.length < 44) {
                log.warn("TTS HTTP 响应为空或过短 url={}", truncateUrl(url));
                return null;
            }
            Files.write(out, body);
            try {
                com.aicall.util.TelephonyWavUtil.convertFileToTelephony8k(out);
            } catch (Exception e) {
                log.warn("HTTP TTS wav 转 8kHz 失败: {}", e.getMessage());
            }
            String base = normalizePlaybackBase();
            String playbackUrl = base + "/uploads/tts/" + fileName;
            log.info("TTS 文件已生成 {} bytes url={}", body.length, playbackUrl);
            return playbackUrl;
        } catch (Exception e) {
            log.warn("HTTP TTS 失败: {}", e.getMessage());
            return null;
        }
    }

    private String normalizePlaybackBase() {
        String base = aiVoiceProperties.getPlaybackBaseUrl();
        if (!StringUtils.hasText(base)) {
            base = "http://127.0.0.1:8081";
        }
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    private String truncateUrl(String url) {
        return url.length() > 80 ? url.substring(0, 80) + "..." : url;
    }
}
