package com.aicall.service;

import com.aicall.config.AiVoiceProperties;
import com.aicall.util.DialogTranscriptLog;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.file.Path;

/**
 * 外呼接通后播放开场白：播预录音（CosyVoice 复刻音色），接通时不调模型。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OpeningPlaybackService {

    private final AiVoiceProperties aiVoiceProperties;
    private final FreeSwitchEslService eslService;
    private final OllamaChatService ollamaChatService;
    private final CallDialogPersistService callDialogPersistService;
    private final CallSessionRecordService callSessionRecordService;
    private final VoicePlaybackService voicePlaybackService;
    private final OpeningVoicePrewarmService openingVoicePrewarmService;
    private final FixedPhrasePlaybackService fixedPhrasePlaybackService;
    private final VoiceRuntimeSettingsService voiceRuntimeSettingsService;
    private final OpeningPlaybackGuard openingPlaybackGuard;

    /**
     * @return 是否已向 FS 下发播放（通道不存在时 false；已播过则 true 且不再重复播）
     */
    public boolean playOpening(String uuid, Integer callRecordId) throws Exception {
        if (!StringUtils.hasText(uuid) || callRecordId == null) {
            return false;
        }
        if (!openingPlaybackGuard.markPlayedIfAbsent(uuid)) {
            log.info("[开场白] 本通道已播过，跳过重复 uuid={} recordId={}", uuid, callRecordId);
            return true;
        }
        if (!voiceRuntimeSettingsService.isPlayOpeningOnAnswer()) {
            log.info("[开场白] 后台已关闭接通播报 uuid={} recordId={}", uuid, callRecordId);
            return true;
        }
        if (!eslService.uuidExists(uuid)) {
            log.warn("[开场白] 通道已结束 uuid={}", uuid);
            return false;
        }
        String opening = resolveOpeningText();
        DialogTranscriptLog.opening(callRecordId, uuid, opening);
        callDialogPersistService.appendAssistant(callRecordId, opening);

        long t0 = System.currentTimeMillis();
        eslService.ensureOutboundMediaReady(uuid);
        int settleMs = Math.max(0, aiVoiceProperties.getAnswerPlayDelayMs());
        if (settleMs > 0) {
            Thread.sleep(settleMs);
        }

        boolean played = tryPlayCachedOpening(uuid);
        if (!played) {
            log.warn("[开场白] 无预录音，改用实时 CosyVoice TTS uuid={} recordId={}", uuid, callRecordId);
            played = aiVoiceProperties.isTtsStreamEnabled()
                    ? voicePlaybackService.playTextStreaming(uuid, opening)
                    : voicePlaybackService.playOnChannel(uuid, opening);
        }
        if (!played) {
            log.error("[开场白] 预录音与实时 TTS 均失败 uuid={} recordId={}，请检查 CosyVoice 配置与 FS 播放路径",
                    uuid, callRecordId);
            return false;
        }
        voicePlaybackService.waitPlaybackFinished(uuid, opening);
        callSessionRecordService.syncAsrBaselineAfterPlayback(uuid);
        log.info("[开场白] 播完 uuid={} recordId={} mode=turn-based 接通至开播白约{}ms",
                uuid, callRecordId, System.currentTimeMillis() - t0);
        return true;
    }

    /** 挂断/结束语：预录音 → CosyVoice/本地 SAPI，播完再返回 */
    public boolean playEnding(String uuid, Integer callRecordId, String endText) throws Exception {
        if (!StringUtils.hasText(uuid) || !StringUtils.hasText(endText) || !eslService.uuidExists(uuid)) {
            return false;
        }
        boolean played = fixedPhrasePlaybackService.playCachedEnding(uuid, callRecordId, endText);
        if (!played) {
            log.warn("[结束语] 无预录音，尝试 CosyVoice/本地播报 uuid={}", uuid);
            played = aiVoiceProperties.isTtsStreamEnabled()
                    ? voicePlaybackService.playTextStreaming(uuid, endText)
                    : voicePlaybackService.playOnChannel(uuid, endText);
        }
        if (played) {
            voicePlaybackService.waitPlaybackFinished(uuid, endText);
            callSessionRecordService.syncAsrBaselineAfterPlayback(uuid);
            return true;
        }
        return false;
    }

    private boolean tryPlayCachedOpening(String uuid) throws Exception {
        if (fixedPhrasePlaybackService.playCachedOpening(uuid)) {
            return true;
        }
        long waitMs = Math.max(0, aiVoiceProperties.getOpeningPrewarmWaitMs());
        Path prewarmed = openingVoicePrewarmService.awaitSharedWav(uuid, waitMs);
        if (prewarmed != null) {
            log.info("[开场白] 预合成即播 uuid={} path={}", uuid, prewarmed);
            return voicePlaybackService.playOutboundOpeningWav(uuid, prewarmed);
        }
        return false;
    }

    public String resolveOpeningText() {
        try {
            String opening = ollamaChatService.activePrompt().getOpeningRemarks();
            if (StringUtils.hasText(opening)) {
                return opening.trim();
            }
        } catch (Exception e) {
            log.warn("[开场白] 读取话术失败: {}", e.getMessage());
        }
        return "您好，我是智能外呼助手，很高兴为您服务。";
    }
}
