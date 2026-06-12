package com.aicall.common;

import org.springframework.util.StringUtils;

/**
 * CosyVoice 外呼模型：定制复刻 → cosyvoice-v3-plus；系统预置 → cosyvoice-v3-flash。
 * 复刻 voice_id 前缀可推断复刻时的 target_model（兼容历史 v3.5 音色）。
 */
public final class CosyVoiceModelRules {

    public static final String CLONE_TTS_MODEL = "cosyvoice-v3-plus";
    public static final String SYSTEM_TTS_MODEL = "cosyvoice-v3-flash";

    private CosyVoiceModelRules() {
    }

    /** 定制复刻外呼合成模型（优先从 voice_id 前缀推断，避免 418） */
    public static String resolveCloneTtsModel(String cloneVoiceId, String ymlModel) {
        String inferred = inferEnrollmentTargetModel(cloneVoiceId);
        if (StringUtils.hasText(inferred)) {
            return inferred;
        }
        if (StringUtils.hasText(ymlModel)) {
            return ymlModel.trim();
        }
        return CLONE_TTS_MODEL;
    }

    /** 声音复刻 create_voice 的 target_model */
    public static String resolveCloneEnrollmentTargetModel(String ymlModel) {
        if (StringUtils.hasText(ymlModel)) {
            return ymlModel.trim();
        }
        return CLONE_TTS_MODEL;
    }

    /**
     * 从复刻 voice_id 前缀推断 target_model（如 cosyvoice-v3.5-plus-cf-xxx → v3.5-plus）。
     */
    public static String inferEnrollmentTargetModel(String voiceId) {
        if (!StringUtils.hasText(voiceId)) {
            return null;
        }
        String v = voiceId.trim().toLowerCase();
        if (v.startsWith("cosyvoice-v3.5-plus")) {
            return "cosyvoice-v3.5-plus";
        }
        if (v.startsWith("cosyvoice-v3.5-flash")) {
            return "cosyvoice-v3.5-flash";
        }
        if (v.startsWith("cosyvoice-v3-plus")) {
            return "cosyvoice-v3-plus";
        }
        if (v.startsWith("cosyvoice-v3-flash")) {
            return "cosyvoice-v3-flash";
        }
        if (v.startsWith("cosyvoice-v2")) {
            return "cosyvoice-v2-plus";
        }
        return null;
    }
}
