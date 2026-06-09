package com.aicall.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 阿里云通义千问 DashScope（OpenAI 兼容接口）。
 * API Key 在阿里云控制台 → 模型服务灵积 DashScope → API-KEY 管理 创建。
 */
@Data
@Component
@ConfigurationProperties(prefix = "dashscope")
public class DashScopeProperties {

    /** 配置 api-key 后启动时若数据库无通义千问记录则写入 ai_model_config（兜底） */
    private boolean enabled = true;
    /** 兜底：环境变量 DASHSCOPE_API_KEY 或此处填写；正常运行时优先读数据库模型配置 */
    private String apiKey = "";
    private String baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";
    /** qwen-turbo | qwen-plus | qwen-max 等 */
    private String model = "qwen-plus";
    private int maxTokens = 80;
    private double temperature = 0.7;
    /** nucleus sampling，外呼低延迟场景可设 0.1 */
    private double topP = 1.0;
    private int maxHistoryRounds = 3;
    private int connectTimeoutMs = 5000;
    private int readTimeoutMs = 60000;
}
