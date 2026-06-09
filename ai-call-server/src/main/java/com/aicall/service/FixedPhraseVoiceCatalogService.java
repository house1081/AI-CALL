package com.aicall.service;

import com.aicall.config.AiVoiceProperties;
import com.aicall.dto.CosyVoiceVoiceItemDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 固定话术预生成涉及的 CosyVoice 音色目录：当前配置 + yml 默认 + DashScope 已复刻列表。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FixedPhraseVoiceCatalogService {

    private final VoiceRuntimeSettingsService voiceRuntimeSettingsService;
    private final AiVoiceProperties aiVoiceProperties;
    private final ObjectProvider<CosyVoiceVoiceEnrollmentService> cosyVoiceEnrollmentProvider;

    /** 外呼实际使用的 CosyVoice voice_id */
    public String resolveActiveVoiceId() {
        String db = voiceRuntimeSettingsService.getCosyvoiceCloneVoiceId();
        if (StringUtils.hasText(db)) {
            return db.trim();
        }
        String yml = aiVoiceProperties.getTtsCloneVoiceId();
        return StringUtils.hasText(yml) ? yml.trim() : "";
    }

    /** 需要预生成开场白/结束语的全部音色（去重、保序） */
    public List<String> listAllVoiceIds() {
        Set<String> ids = new LinkedHashSet<>();
        addVoice(ids, resolveActiveVoiceId());
        addVoice(ids, aiVoiceProperties.getTtsCloneVoiceId());
        try {
            CosyVoiceVoiceEnrollmentService enroll = cosyVoiceEnrollmentProvider.getIfAvailable();
            if (enroll != null) {
                for (CosyVoiceVoiceItemDto item : enroll.listVoices()) {
                    if (item != null && StringUtils.hasText(item.getVoiceId())) {
                        addVoice(ids, item.getVoiceId());
                    }
                }
            }
        } catch (Exception e) {
            log.debug("[固定话术] 拉取 CosyVoice 音色列表跳过: {}", e.getMessage());
        }
        if (ids.isEmpty()) {
            log.warn("[固定话术] 无可用 CosyVoice voice_id，跳过预生成");
        }
        return new ArrayList<>(ids);
    }

    /** 用作缓存目录名，避免路径非法字符 */
    public static String voiceKey(String voiceId) {
        if (!StringUtils.hasText(voiceId)) {
            return "_default";
        }
        String key = voiceId.trim().replaceAll("[^a-zA-Z0-9._-]", "_");
        return key.length() > 120 ? key.substring(0, 120) : key;
    }

    private static void addVoice(Set<String> ids, String voiceId) {
        if (StringUtils.hasText(voiceId)) {
            ids.add(voiceId.trim());
        }
    }
}
