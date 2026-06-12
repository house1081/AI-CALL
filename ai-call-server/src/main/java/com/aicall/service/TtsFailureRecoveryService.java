package com.aicall.service;

import com.aicall.common.ForcedHangupRules;
import com.aicall.common.TtsSynthesisException;
import com.aicall.util.DialogTranscriptLog;
import com.aicall.config.AiVoiceProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * TTS 合成异常（428/429 等）时：先播结束语（预录音 / 本地 SAPI），播完再挂断。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TtsFailureRecoveryService {

    private final AiVoiceProperties aiVoiceProperties;
    private final FreeSwitchEslService eslService;
    private final OpeningPlaybackService openingPlaybackService;
    private final VoicePlaybackService voicePlaybackService;
    private final CallDialogPersistService callDialogPersistService;

    public void playEndingAndHangup(String uuid, Integer callRecordId, TtsSynthesisException cause) {
        String reason = cause != null ? cause.getMessage() : "TTS 失败";
        boolean rateLimited = cause != null && cause.isRateLimited();
        log.warn("[TTS] 合成异常 uuid={} rateLimit={} → 播放结束语并挂断: {}",
                uuid, rateLimited, reason);
        playEndingThenHangup(uuid, callRecordId, ForcedHangupRules.TTS_FAILURE_END_WORDS,
                rateLimited ? "tts-rate-limit" : "tts-failure");
    }

    /** 先播结束语并等待播完，再挂断（预录音 → CosyVoice → Windows SAPI） */
    public void playEndingThenHangup(String uuid, Integer callRecordId, String endingText, String hangupReason) {
        if (!StringUtils.hasText(uuid) || !eslService.uuidExists(uuid)) {
            return;
        }
        String ending = StringUtils.hasText(endingText) ? endingText.trim() : ForcedHangupRules.END_WORDS;
        try {
            DialogTranscriptLog.aiReply(callRecordId, uuid, ending,
                    StringUtils.hasText(hangupReason) ? hangupReason : "ending", true);
            if (callRecordId != null) {
                callDialogPersistService.appendAssistant(callRecordId, ending);
            }
            playEndingAndWait(uuid, callRecordId, ending);
        } catch (Exception e) {
            log.warn("[TTS] 结束语播放失败 uuid={}: {}", uuid, e.getMessage());
        } finally {
            if (eslService.uuidExists(uuid)) {
                eslService.hangupChannel(uuid, StringUtils.hasText(hangupReason) ? hangupReason : "ending-hangup");
            }
        }
    }

    private void playEndingAndWait(String uuid, Integer callRecordId, String ending) throws Exception {
        boolean played = openingPlaybackService.playEnding(uuid, callRecordId, ending);
        if (played) {
            return;
        }
        log.warn("[TTS] 结束语标准路径未播出 uuid={}，尝试本地 SAPI 回退", uuid);
        if (voicePlaybackService.playOnChannel(uuid, ending)) {
            voicePlaybackService.waitPlaybackFinished(uuid, ending);
            return;
        }
        int tailMs = Math.max(300, aiVoiceProperties.getPlaybackTailBufferMs());
        log.warn("[TTS] 结束语无法播出 uuid={}，等待 {}ms 后挂断", uuid, tailMs);
        Thread.sleep(tailMs);
    }
}
