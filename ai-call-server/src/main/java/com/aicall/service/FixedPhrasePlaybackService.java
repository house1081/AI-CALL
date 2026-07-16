package com.aicall.service;

import com.aicall.entity.AiPrompt;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 固定话术（开场白/结束语）播放：智能预录模式播放上传录音；AI 实时模式播放 CosyVoice 预合成缓存。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FixedPhrasePlaybackService {

    private final OpeningVoiceCacheService openingVoiceCacheService;
    private final EndingVoiceCacheService endingVoiceCacheService;
    private final VoicePlaybackService voicePlaybackService;
    private final CallSessionRecordService callSessionRecordService;
    private final VoiceRuntimeSettingsService voiceRuntimeSettingsService;
    private final AiPromptRecordingService aiPromptRecordingService;

    /** @return 是否已下发 FS 播放 */
    public boolean playCachedOpening(String uuid) throws Exception {
        if (voiceRuntimeSettingsService.isSmartPrerecordMode()) {
            return playUploadedOpening(uuid);
        }
        Path wav = openingVoiceCacheService.copyToCallPlayback(uuid);
        if (wav == null) {
            return false;
        }
        log.info("[固定话术] 开场白预录音 uuid={}", uuid);
        return voicePlaybackService.playOutboundOpeningWav(uuid, wav);
    }

    /** @return 是否已下发 FS 播放；无预录音时不调 TTS */
    public boolean playCachedEnding(String uuid, Integer callRecordId, String endText) throws Exception {
        if (voiceRuntimeSettingsService.isSmartPrerecordMode()) {
            return playUploadedEnding(uuid, callRecordId);
        }
        if (!StringUtils.hasText(endText)) {
            return false;
        }
        Path wav = endingVoiceCacheService.copyToCallPlayback(uuid, endText);
        if (wav == null) {
            return false;
        }
        log.info("[固定话术] 结束语预录音 uuid={} text={}", uuid,
                endText.length() > 36 ? endText.substring(0, 36) + "…" : endText);
        boolean ok = voicePlaybackService.playOutboundOpeningWav(uuid, wav);
        if (ok && callRecordId != null) {
            callSessionRecordService.syncAsrBaselineAfterPlayback(uuid);
        }
        return ok;
    }

    private boolean playUploadedOpening(String uuid) throws Exception {
        AiPrompt active = aiPromptRecordingService.loadActivePrompt();
        Path src = aiPromptRecordingService.resolveOpeningWav(active);
        if (src == null) {
            log.warn("[固定话术] 智能预录无开场白上传录音 uuid={}", uuid);
            return false;
        }
        Path playback = copyToCallPlayback(uuid, src, "opening");
        if (playback == null) {
            return false;
        }
        log.info("[固定话术] 播放上传开场白 uuid={} path={}", uuid, src.getFileName());
        return voicePlaybackService.playOutboundOpeningWav(uuid, playback);
    }

    private boolean playUploadedEnding(String uuid, Integer callRecordId) throws Exception {
        AiPrompt active = aiPromptRecordingService.loadActivePrompt();
        Path src = aiPromptRecordingService.resolveEndingWav(active);
        if (src == null) {
            log.warn("[固定话术] 智能预录无结束语上传录音 uuid={}", uuid);
            return false;
        }
        Path playback = copyToCallPlayback(uuid, src, "ending");
        if (playback == null) {
            return false;
        }
        log.info("[固定话术] 播放上传结束语 uuid={} path={}", uuid, src.getFileName());
        boolean ok = voicePlaybackService.playOutboundOpeningWav(uuid, playback);
        if (ok && callRecordId != null) {
            callSessionRecordService.syncAsrBaselineAfterPlayback(uuid);
        }
        return ok;
    }

    private Path copyToCallPlayback(String uuid, Path src, String suffix) {
        try {
            Path target = Path.of("./uploads/tts/prompt-playback",
                    "aicall_" + suffix + "_" + uuid.replace("-", "") + ".wav");
            Files.createDirectories(target.getParent());
            Files.copy(src, target, StandardCopyOption.REPLACE_EXISTING);
            return target;
        } catch (Exception e) {
            log.warn("[固定话术] 拷贝上传录音失败 uuid={}: {}", uuid, e.getMessage());
            return null;
        }
    }
}
