package com.aicall.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("ai_model_config")
public class AiModelConfig {
    @TableId(type = IdType.AUTO)
    private Integer id;
    private String configName;
    /** ollama | qwen | wenxin */
    private String provider;
    private String baseUrl;
    private String modelName;
    private String apiKey;
    private String secretKey;
    private Integer maxTokens;
    private BigDecimal temperature;
    /** 非持久化：外呼对话可覆盖 top_p */
    @TableField(exist = false)
    private Double topP;
    private Integer maxHistoryRounds;
    private Integer connectTimeoutMs;
    private Integer readTimeoutMs;
    private Integer isActive;
    private String remark;
    private LocalDateTime updateTime;
}
