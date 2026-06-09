package com.aicall.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "ollama")
public class OllamaProperties {
    /** Ollama 服务地址，如 http://192.168.60.28:11434 */
    private String baseUrl = "http://192.168.60.28:11434";
    /** 模型名，与 ollama list 一致 */
    private String model = "qwen:4b";
    private int connectTimeoutMs = 5000;
    private int readTimeoutMs = 60000;
    /** 单次回复最大 token，控制电话场景 1～2 句话 */
    private int maxTokens = 80;
    private double temperature = 0.7;
    /** 上下文轮数（user+assistant 算一轮） */
    private int maxHistoryRounds = 3;
}
