package com.aicall.service.prerecord;

import com.aicall.common.DialogSlotHelper;
import com.aicall.common.ForcedHangupRules;
import com.aicall.config.AiVoiceProperties;
import com.aicall.entity.PrerecordFaq;
import lombok.Data;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AI 实时外呼下的录音熔断：TTS 连续失败或客户连续抱怨答非所问时切入预录分支。
 */
@Service
public class PrerecordCircuitService {

    private static final int OFF_TOPIC_THRESHOLD = 2;
    private static final int TTS_FAIL_THRESHOLD = 2;

    private final AiVoiceProperties aiVoiceProperties;
    private final Map<String, CallState> states = new ConcurrentHashMap<>();

    public PrerecordCircuitService(AiVoiceProperties aiVoiceProperties) {
        this.aiVoiceProperties = aiVoiceProperties;
    }

    @Data
    private static class CallState {
        private boolean circuitActive;
        private int offTopicStrikes;
        private int ttsFailStrikes;
    }

    public void bindCall(String fsUuid) {
        if (fsUuid != null) {
            states.putIfAbsent(fsUuid.trim(), new CallState());
        }
    }

    public void clearCall(String fsUuid) {
        if (fsUuid != null) {
            states.remove(fsUuid.trim());
        }
    }

    public boolean isCircuitActive(String fsUuid) {
        CallState s = state(fsUuid);
        return s != null && s.circuitActive;
    }

    public void recordOffTopicComplaint(String fsUuid, String userText) {
        if (!aiVoiceProperties.isPrerecordCircuitEnabled() || !org.springframework.util.StringUtils.hasText(userText)) {
            return;
        }
        if (!DialogSlotHelper.isFollowUpComplaint(userText)) {
            return;
        }
        CallState s = stateOrCreate(fsUuid);
        s.offTopicStrikes++;
        if (s.offTopicStrikes >= OFF_TOPIC_THRESHOLD) {
            s.circuitActive = true;
        }
    }

    public void recordTtsFailure(String fsUuid) {
        if (!aiVoiceProperties.isPrerecordCircuitEnabled()) {
            return;
        }
        CallState s = stateOrCreate(fsUuid);
        s.ttsFailStrikes++;
        if (s.ttsFailStrikes >= TTS_FAIL_THRESHOLD) {
            s.circuitActive = true;
        }
    }

    public boolean shouldUsePrerecord(String fsUuid, boolean smartPrerecordMode) {
        if (smartPrerecordMode) {
            return true;
        }
        return isCircuitActive(fsUuid);
    }

    private CallState state(String fsUuid) {
        if (!org.springframework.util.StringUtils.hasText(fsUuid)) {
            return null;
        }
        return states.get(fsUuid.trim());
    }

    private CallState stateOrCreate(String fsUuid) {
        return states.computeIfAbsent(fsUuid.trim(), k -> new CallState());
    }
}
