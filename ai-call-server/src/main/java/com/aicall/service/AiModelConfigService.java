package com.aicall.service;

import com.aicall.common.AiModelProvider;
import com.aicall.common.BizException;
import com.aicall.config.OllamaProperties;
import com.aicall.config.DashScopeProperties;
import com.aicall.dto.AiModelConfigVO;
import com.aicall.entity.AiModelConfig;
import com.aicall.mapper.AiModelConfigMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

@Service
@RequiredArgsConstructor
public class AiModelConfigService {

    private final AiModelConfigMapper aiModelConfigMapper;
    private final OllamaProperties ollamaProperties;
    private final DashScopeProperties dashScopeProperties;
    private final AtomicReference<AiModelConfig> activeCache = new AtomicReference<>();

    public AiModelConfig requireActive() {
        AiModelConfig cached = activeCache.get();
        if (cached != null) {
            return cached;
        }
        AiModelConfig db = aiModelConfigMapper.selectOne(
                new LambdaQueryWrapper<AiModelConfig>().eq(AiModelConfig::getIsActive, 1).last("LIMIT 1"));
        if (db != null) {
            activeCache.set(db);
            return db;
        }
        AiModelConfig fallback = fallbackFromYml();
        activeCache.set(fallback);
        return fallback;
    }

    public void evictCache() {
        activeCache.set(null);
    }

    /**
     * DashScope API Key：优先数据库「模型配置」中通义千问（qwen）记录的 Key，
     * 与当前 LLM 对话启用哪条配置无关（外呼 ASR/TTS 均走此 Key）。
     */
    public String resolveQwenApiKey() {
        AiModelConfig activeQwen = aiModelConfigMapper.selectOne(
                new LambdaQueryWrapper<AiModelConfig>()
                        .eq(AiModelConfig::getProvider, AiModelProvider.QWEN)
                        .eq(AiModelConfig::getIsActive, 1)
                        .last("LIMIT 1"));
        if (activeQwen != null && StringUtils.hasText(activeQwen.getApiKey())) {
            return activeQwen.getApiKey().trim();
        }
        AiModelConfig anyQwen = aiModelConfigMapper.selectOne(
                new LambdaQueryWrapper<AiModelConfig>()
                        .eq(AiModelConfig::getProvider, AiModelProvider.QWEN)
                        .isNotNull(AiModelConfig::getApiKey)
                        .ne(AiModelConfig::getApiKey, "")
                        .orderByDesc(AiModelConfig::getIsActive)
                        .orderByDesc(AiModelConfig::getId)
                        .last("LIMIT 1"));
        if (anyQwen != null && StringUtils.hasText(anyQwen.getApiKey())) {
            return anyQwen.getApiKey().trim();
        }
        return "";
    }

    public List<AiModelConfigVO> listAll() {
        List<AiModelConfig> rows = aiModelConfigMapper.selectList(
                new LambdaQueryWrapper<AiModelConfig>().orderByDesc(AiModelConfig::getIsActive).orderByDesc(AiModelConfig::getId));
        List<AiModelConfigVO> list = new ArrayList<>();
        for (AiModelConfig c : rows) {
            list.add(toVo(c));
        }
        return list;
    }

    public AiModelConfigVO getVo(Integer id) {
        AiModelConfig c = aiModelConfigMapper.selectById(id);
        if (c == null) {
            throw new BizException("模型配置不存在");
        }
        return toVo(c);
    }

    @Transactional
    public void save(AiModelConfig incoming) {
        validate(incoming);
        if (incoming.getId() != null) {
            AiModelConfig old = aiModelConfigMapper.selectById(incoming.getId());
            if (old == null) {
                throw new BizException("模型配置不存在");
            }
            old.setConfigName(incoming.getConfigName());
            old.setProvider(incoming.getProvider());
            old.setBaseUrl(incoming.getBaseUrl());
            old.setModelName(incoming.getModelName());
            if (StringUtils.hasText(incoming.getApiKey())) {
                old.setApiKey(incoming.getApiKey());
            }
            if (StringUtils.hasText(incoming.getSecretKey())) {
                old.setSecretKey(incoming.getSecretKey());
            }
            old.setMaxTokens(incoming.getMaxTokens());
            old.setTemperature(incoming.getTemperature());
            old.setMaxHistoryRounds(incoming.getMaxHistoryRounds());
            old.setConnectTimeoutMs(incoming.getConnectTimeoutMs());
            old.setReadTimeoutMs(incoming.getReadTimeoutMs());
            if (incoming.getRemark() != null) {
                old.setRemark(incoming.getRemark());
            }
            aiModelConfigMapper.updateById(old);
        } else {
            if (incoming.getIsActive() == null) {
                incoming.setIsActive(0);
            }
            aiModelConfigMapper.insert(incoming);
        }
        evictCache();
    }

    @Transactional
    public void activate(Integer id) {
        if (aiModelConfigMapper.selectById(id) == null) {
            throw new BizException("模型配置不存在");
        }
        aiModelConfigMapper.update(null, new LambdaUpdateWrapper<AiModelConfig>()
                .set(AiModelConfig::getIsActive, 0));
        aiModelConfigMapper.update(null, new LambdaUpdateWrapper<AiModelConfig>()
                .eq(AiModelConfig::getId, id)
                .set(AiModelConfig::getIsActive, 1));
        evictCache();
    }

    private void validate(AiModelConfig cfg) {
        if (!StringUtils.hasText(cfg.getConfigName())) {
            throw new BizException("请填写配置名称");
        }
        if (!AiModelProvider.isValid(cfg.getProvider())) {
            throw new BizException("提供方须为 ollama / qwen / wenxin");
        }
        if (!StringUtils.hasText(cfg.getModelName())) {
            throw new BizException("请填写模型名称");
        }
        if (AiModelProvider.QWEN.equals(cfg.getProvider()) && !StringUtils.hasText(cfg.getApiKey())
                && (cfg.getId() == null)) {
            throw new BizException("通义千问需填写 API Key");
        }
        if (AiModelProvider.WENXIN.equals(cfg.getProvider())) {
            if (cfg.getId() == null && (!StringUtils.hasText(cfg.getApiKey()) || !StringUtils.hasText(cfg.getSecretKey()))) {
                throw new BizException("文心一言需填写 API Key 与 Secret Key");
            }
        }
        if (cfg.getMaxTokens() == null || cfg.getMaxTokens() < 16) {
            cfg.setMaxTokens(80);
        }
        if (cfg.getMaxHistoryRounds() == null || cfg.getMaxHistoryRounds() < 1) {
            cfg.setMaxHistoryRounds(3);
        }
        if (cfg.getTemperature() == null) {
            cfg.setTemperature(BigDecimal.valueOf(0.7));
        }
        if (cfg.getConnectTimeoutMs() == null) {
            cfg.setConnectTimeoutMs(5000);
        }
        if (cfg.getReadTimeoutMs() == null) {
            cfg.setReadTimeoutMs(60000);
        }
        applyDefaultBaseUrl(cfg);
    }

    private void applyDefaultBaseUrl(AiModelConfig cfg) {
        if (StringUtils.hasText(cfg.getBaseUrl())) {
            return;
        }
        switch (cfg.getProvider()) {
            case AiModelProvider.OLLAMA -> cfg.setBaseUrl(ollamaProperties.getBaseUrl());
            case AiModelProvider.QWEN -> cfg.setBaseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1");
            case AiModelProvider.WENXIN -> cfg.setBaseUrl(
                    "https://aip.baidubce.com/rpc/2.0/ai_custom/v1/wenxinworkshop/chat/completions");
            default -> {
            }
        }
    }

    private AiModelConfig fallbackFromYml() {
        if (dashScopeProperties.isEnabled() && StringUtils.hasText(dashScopeProperties.getApiKey())) {
            AiModelConfig c = new AiModelConfig();
            c.setId(0);
            c.setConfigName("dashscope.yml");
            c.setProvider(AiModelProvider.QWEN);
            c.setBaseUrl(dashScopeProperties.getBaseUrl());
            c.setModelName(dashScopeProperties.getModel());
            c.setApiKey(dashScopeProperties.getApiKey().trim());
            c.setMaxTokens(dashScopeProperties.getMaxTokens());
            c.setTemperature(BigDecimal.valueOf(dashScopeProperties.getTemperature()));
            c.setTopP(dashScopeProperties.getTopP());
            c.setMaxHistoryRounds(dashScopeProperties.getMaxHistoryRounds());
            c.setConnectTimeoutMs(dashScopeProperties.getConnectTimeoutMs());
            c.setReadTimeoutMs(dashScopeProperties.getReadTimeoutMs());
            return c;
        }
        AiModelConfig c = new AiModelConfig();
        c.setId(0);
        c.setConfigName("application.yml 默认");
        c.setProvider(AiModelProvider.OLLAMA);
        c.setBaseUrl(ollamaProperties.getBaseUrl());
        c.setModelName(ollamaProperties.getModel());
        c.setMaxTokens(ollamaProperties.getMaxTokens());
        c.setTemperature(BigDecimal.valueOf(ollamaProperties.getTemperature()));
        c.setMaxHistoryRounds(ollamaProperties.getMaxHistoryRounds());
        c.setConnectTimeoutMs(ollamaProperties.getConnectTimeoutMs());
        c.setReadTimeoutMs(ollamaProperties.getReadTimeoutMs());
        return c;
    }

    private AiModelConfigVO toVo(AiModelConfig c) {
        AiModelConfigVO vo = new AiModelConfigVO();
        vo.setId(c.getId());
        vo.setConfigName(c.getConfigName());
        vo.setProvider(c.getProvider());
        vo.setBaseUrl(c.getBaseUrl());
        vo.setModelName(c.getModelName());
        vo.setMaxTokens(c.getMaxTokens());
        vo.setTemperature(c.getTemperature());
        vo.setMaxHistoryRounds(c.getMaxHistoryRounds());
        vo.setConnectTimeoutMs(c.getConnectTimeoutMs());
        vo.setReadTimeoutMs(c.getReadTimeoutMs());
        vo.setIsActive(c.getIsActive());
        vo.setRemark(c.getRemark());
        vo.setUpdateTime(c.getUpdateTime());
        vo.setApiKeySet(StringUtils.hasText(c.getApiKey()));
        vo.setSecretKeySet(StringUtils.hasText(c.getSecretKey()));
        return vo;
    }
}
