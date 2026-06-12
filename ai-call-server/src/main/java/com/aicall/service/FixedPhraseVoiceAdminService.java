package com.aicall.service;

import com.aicall.common.BizException;
import com.aicall.config.AiVoiceProperties;
import com.aicall.dto.FixedVoicePrecacheStatusDto;
import com.aicall.entity.AiPrompt;
import com.aicall.mapper.AiPromptMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 管理端：固定话术预录音状态查询与手动同步预生成。
 */
@Service
@RequiredArgsConstructor
public class FixedPhraseVoiceAdminService {

    private final AiVoiceProperties aiVoiceProperties;
    private final AiPromptMapper aiPromptMapper;
    private final TtsPhraseCacheService ttsPhraseCacheService;
    private final DashScopeVoiceTtsService dashScopeVoiceTtsService;
    private final VoiceRuntimeSettingsService voiceRuntimeSettingsService;
    private final FixedPhraseVoiceCatalogService voiceCatalog;
    private final OpeningVoiceCacheService openingVoiceCacheService;
    private final EndingVoiceCacheService endingVoiceCacheService;

    public FixedVoicePrecacheStatusDto getStatus() {
        AiPrompt active = loadActivePrompt();
        return buildStatus(active, null);
    }

    /** 为全部 CosyVoice 音色同步预生成开场白与结束语 */
    public FixedVoicePrecacheStatusDto precacheActiveSync() {
        if (!aiVoiceProperties.isOpeningVoicePrecacheEnabled()) {
            throw new BizException("固定话术预生成已在配置中关闭（opening-voice-precache-enabled=false）");
        }
        if (!ttsPhraseCacheService.isAvailable()) {
            throw new BizException("CosyVoice TTS 不可用，请先配置 DashScope API Key 与 CosyVoice 复刻 voice_id");
        }
        AiPrompt active = loadActivePrompt();
        if (active == null) {
            throw new BizException("请先启用一套话术模板后再预生成");
        }
        if (voiceCatalog.listAllVoiceIds().isEmpty()) {
            throw new BizException("未找到 CosyVoice 音色，请先配置或创建复刻音色");
        }

        if (StringUtils.hasText(active.getOpeningRemarks())) {
            openingVoiceCacheService.ensureActiveOpeningCached(true);
        }
        endingVoiceCacheService.regenerateAll(active);

        return buildStatus(active, resolveResultMessage(active));
    }

    private AiPrompt loadActivePrompt() {
        return aiPromptMapper.selectOne(
                new LambdaQueryWrapper<AiPrompt>().eq(AiPrompt::getIsActive, 1).last("LIMIT 1"));
    }

    private FixedVoicePrecacheStatusDto buildStatus(AiPrompt active, String message) {
        FixedVoicePrecacheStatusDto dto = new FixedVoicePrecacheStatusDto();
        dto.setPrecacheEnabled(aiVoiceProperties.isOpeningVoicePrecacheEnabled());
        dto.setTtsModel(aiVoiceProperties.getTtsModel());
        dto.setTtsVoice(maskVoiceId(voiceCatalog.resolveActiveVoiceId()));
        dto.setVoiceSignature(dashScopeVoiceTtsService.voiceCacheSignature(voiceCatalog.resolveActiveVoiceId()));
        int total = voiceCatalog.listAllVoiceIds().size();
        dto.setTotalVoiceCount(total);
        dto.setOpeningReadyCount(openingVoiceCacheService.countReadyVoices());
        dto.setEndingReadyCount(endingVoiceCacheService.countReadyVoices());
        dto.setOpeningReady(dto.getOpeningReadyCount() != null && dto.getOpeningReadyCount() > 0
                && dto.getOpeningReadyCount().equals(total));
        dto.setEndingReady(dto.getEndingReadyCount() != null && dto.getEndingReadyCount() > 0
                && dto.getEndingReadyCount().equals(total));
        if (active != null) {
            dto.setActivePromptId(active.getId());
            dto.setOpeningRemarks(active.getOpeningRemarks());
            dto.setEndRemarks(active.getEndRemarks());
        }
        if (StringUtils.hasText(message)) {
            dto.setMessage(message);
        } else if (Boolean.TRUE.equals(dto.getOpeningReady()) && Boolean.TRUE.equals(dto.getEndingReady())) {
            dto.setMessage("全部音色预生成已完成，外呼将按当前音色播放预录音");
        } else if (total > 0) {
            dto.setMessage(String.format("预生成进度：开场白 %d/%d，结束语 %d/%d（修改话术/音色后会自动排队）",
                    dto.getOpeningReadyCount(), total, dto.getEndingReadyCount(), total));
        } else {
            dto.setMessage("尚未配置 CosyVoice 音色，外呼接通/挂断将跳过固定话术播报");
        }
        return dto;
    }

    private String resolveResultMessage(AiPrompt active) {
        int total = voiceCatalog.listAllVoiceIds().size();
        int openingReady = openingVoiceCacheService.countReadyVoices();
        int endingReady = endingVoiceCacheService.countReadyVoices();
        boolean openingNeeded = StringUtils.hasText(active.getOpeningRemarks());
        boolean openingOk = !openingNeeded || openingReady >= total;
        boolean endingOk = endingReady >= total;
        if (openingOk && endingOk) {
            return String.format("预生成完成：%d 个音色的开场白+结束语已就绪", total);
        }
        if (!openingOk && !endingOk) {
            return "预生成部分失败，请检查 CosyVoice 配置及服务端日志";
        }
        if (!openingOk) {
            return String.format("开场白 %d/%d 就绪；结束语 %d/%d", openingReady, total, endingReady, total);
        }
        return String.format("结束语 %d/%d 就绪；开场白 %d/%d", endingReady, total, openingReady, total);
    }

    private static String maskVoiceId(String id) {
        if (!StringUtils.hasText(id)) {
            return "(未配置)";
        }
        if (id.length() <= 16) {
            return id;
        }
        return id.substring(0, 10) + "…" + id.substring(id.length() - 6);
    }
}
