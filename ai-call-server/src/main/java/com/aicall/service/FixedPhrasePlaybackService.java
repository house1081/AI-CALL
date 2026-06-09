package com.aicall.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.file.Path;

/**
 * 固定话术（开场白/结束语）仅播放预录音，接通/挂断不调 TTS 模型。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FixedPhrasePlaybackService {

    private final OpeningVoiceCacheService openingVoiceCacheService;
    private final EndingVoiceCacheService endingVoiceCacheService;
    private final VoicePlaybackService voicePlaybackService;
    private final CallSessionRecordService callSessionRecordService;

    /** @return 是否已下发 FS 播放 */
    public boolean playCachedOpening(String uuid) throws Exception {
        Path wav = openingVoiceCacheService.copyToCallPlayback(uuid);
        if (wav == null) {
            return false;
        }
        log.info("[固定话术] 开场白预录音 uuid={}", uuid);
        return voicePlaybackService.playOutboundOpeningWav(uuid, wav);
    }

    /** @return 是否已下发 FS 播放；无预录音时不调 TTS */
    public boolean playCachedEnding(String uuid, Integer callRecordId, String endText) throws Exception {
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
}
