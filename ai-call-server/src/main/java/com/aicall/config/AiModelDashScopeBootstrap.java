package com.aicall.config;

import com.aicall.common.AiModelProvider;
import com.aicall.entity.AiModelConfig;
import com.aicall.mapper.AiModelConfigMapper;
import com.aicall.service.AiModelConfigService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;

/**
 * application.yml 配置 dashscope.api-key 时，自动启用通义千问为当前大模型。
 */
@Slf4j
@Component
@Order(20)
@RequiredArgsConstructor
public class AiModelDashScopeBootstrap implements ApplicationRunner {

    private final DashScopeProperties dashScopeProperties;
    private final AiModelConfigMapper aiModelConfigMapper;
    private final AiModelConfigService aiModelConfigService;

    @Override
    public void run(ApplicationArguments args) {
        if (!dashScopeProperties.isEnabled() || !StringUtils.hasText(dashScopeProperties.getApiKey())) {
            log.info("【大模型】未配置 dashscope.api-key，使用管理后台「模型配置」中已启用的提供方");
            return;
        }
        AiModelConfig qwen = aiModelConfigMapper.selectOne(
                new LambdaQueryWrapper<AiModelConfig>()
                        .eq(AiModelConfig::getProvider, AiModelProvider.QWEN)
                        .orderByDesc(AiModelConfig::getId)
                        .last("LIMIT 1"));
        if (qwen == null) {
            qwen = new AiModelConfig();
            qwen.setConfigName("通义千问 DashScope (yml)");
            qwen.setProvider(AiModelProvider.QWEN);
            qwen.setBaseUrl(dashScopeProperties.getBaseUrl());
            qwen.setModelName(dashScopeProperties.getModel());
            qwen.setApiKey(dashScopeProperties.getApiKey().trim());
            qwen.setMaxTokens(dashScopeProperties.getMaxTokens());
            qwen.setTemperature(BigDecimal.valueOf(dashScopeProperties.getTemperature()));
            qwen.setMaxHistoryRounds(dashScopeProperties.getMaxHistoryRounds());
            qwen.setConnectTimeoutMs(dashScopeProperties.getConnectTimeoutMs());
            qwen.setReadTimeoutMs(dashScopeProperties.getReadTimeoutMs());
            qwen.setIsActive(1);
            qwen.setRemark("由 application.yml dashscope 自动启用");
            aiModelConfigMapper.insert(qwen);
        } else {
            qwen.setBaseUrl(dashScopeProperties.getBaseUrl());
            qwen.setModelName(dashScopeProperties.getModel());
            qwen.setApiKey(dashScopeProperties.getApiKey().trim());
            qwen.setMaxTokens(dashScopeProperties.getMaxTokens());
            qwen.setTemperature(BigDecimal.valueOf(dashScopeProperties.getTemperature()));
            qwen.setMaxHistoryRounds(dashScopeProperties.getMaxHistoryRounds());
            qwen.setConnectTimeoutMs(dashScopeProperties.getConnectTimeoutMs());
            qwen.setReadTimeoutMs(dashScopeProperties.getReadTimeoutMs());
            aiModelConfigMapper.updateById(qwen);
            aiModelConfigMapper.update(null, new LambdaUpdateWrapper<AiModelConfig>()
                    .set(AiModelConfig::getIsActive, 0));
            aiModelConfigMapper.update(null, new LambdaUpdateWrapper<AiModelConfig>()
                    .eq(AiModelConfig::getId, qwen.getId())
                    .set(AiModelConfig::getIsActive, 1));
        }
        aiModelConfigService.evictCache();
        log.info("【大模型】已启用阿里云通义千问 model={} base={}",
                dashScopeProperties.getModel(), dashScopeProperties.getBaseUrl());
    }
}
