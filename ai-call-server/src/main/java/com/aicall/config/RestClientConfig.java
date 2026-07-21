package com.aicall.config;

import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

@Configuration
public class RestClientConfig {

    @Bean
    public RestTemplate llmRestTemplate(OllamaProperties props, RestTemplateBuilder builder) {
        return builder
                .setConnectTimeout(Duration.ofMillis(props.getConnectTimeoutMs()))
                .setReadTimeout(Duration.ofMillis(Math.max(props.getReadTimeoutMs(), 120000)))
                .build();
    }

    /** ASR 专用：较短超时 + 快速重试，避免 Connection reset 挂 20s+ */
    @Bean
    public RestTemplate asrRestTemplate(AiVoiceProperties voiceProps, RestTemplateBuilder builder) {
        int connectMs = Math.max(3000, voiceProps.getAsrConnectTimeoutMs());
        int readMs = Math.max(8000, voiceProps.getAsrReadTimeoutMs());
        return builder
                .setConnectTimeout(Duration.ofMillis(connectMs))
                .setReadTimeout(Duration.ofMillis(readMs))
                .build();
    }

    /** RAG 嵌入专用：短超时，避免阻塞外呼开口（原先 8s 读超时会空等致客户挂机） */
    @Bean
    public RestTemplate embedRestTemplate(RestTemplateBuilder builder) {
        return builder
                .setConnectTimeout(Duration.ofMillis(1500))
                .setReadTimeout(Duration.ofMillis(1500))
                .build();
    }
}
