package com.aicall.dto;

import com.aicall.entity.AiModelConfig;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class AiModelConfigVO extends AiModelConfig {
    private Boolean apiKeySet;
    private Boolean secretKeySet;
}
