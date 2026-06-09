package com.aicall.service;

import com.aicall.config.DashScopeProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 统一解析 DashScope API Key：优先数据库「模型配置」中通义千问 Key，application.yml / 环境变量作兜底。
 */
@Component
@RequiredArgsConstructor
public class DashScopeApiKeyResolver {

    private final DashScopeProperties dashScopeProperties;
    private final AiModelConfigService aiModelConfigService;

    public boolean isConfigured() {
        return StringUtils.hasText(resolve());
    }

    public String resolve() {
        String fromDb = aiModelConfigService.resolveQwenApiKey();
        if (StringUtils.hasText(fromDb)) {
            return fromDb;
        }
        if (dashScopeProperties.isEnabled() && StringUtils.hasText(dashScopeProperties.getApiKey())) {
            return dashScopeProperties.getApiKey().trim();
        }
        return "";
    }

    public String requireOrThrow(String usage) {
        String key = resolve();
        if (!StringUtils.hasText(key)) {
            throw new IllegalStateException(usage
                    + " 需要配置 DashScope API Key（请在管理端「模型配置」添加并填写通义千问 API Key）");
        }
        return key;
    }
}
