package com.aicall.service;

import com.aicall.common.ForcedHangupRules;
import com.aicall.common.HangupType;
import com.aicall.dto.ForcedHangupSession;
import com.aicall.dto.HangupDecision;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class ForcedHangupService {

    private static final String KEY_PREFIX = "call:hangup:state:";
    private static final Duration TTL = Duration.ofHours(2);
    private static final Pattern AI_HANGUP = Pattern.compile("\\[挂断触发\\]|\\[HANGUP[^\\]]*\\]");

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public void initSession(Integer callRecordId, String trainSessionId, String fsUuid) {
        String key = resolveKey(callRecordId, trainSessionId);
        if (key == null) {
            return;
        }
        ForcedHangupSession s = new ForcedHangupSession();
        s.setConnectedAtEpochSec(System.currentTimeMillis() / 1000);
        s.setInvalidChatRounds(0);
        s.setProbeFailures(0);
        s.setFsUuid(fsUuid);
        saveSession(key, s);
    }

    public void clearSession(Integer callRecordId, String trainSessionId) {
        String key = resolveKey(callRecordId, trainSessionId);
        if (key != null) {
            redisTemplate.delete(key);
        }
    }

    public int elapsedSeconds(Integer callRecordId, String trainSessionId) {
        ForcedHangupSession s = loadSession(resolveKey(callRecordId, trainSessionId));
        return elapsedFrom(s);
    }

    public HangupDecision checkDurationOnly(Integer callRecordId, String trainSessionId) {
        return evaluateBeforeAi(callRecordId, trainSessionId, null, null);
    }

    /**
     * 仅三类规则触发结束：辱骂脏话投诉、明确拒接打扰、通话超时。
     */
    public HangupDecision evaluateBeforeAi(Integer callRecordId, String trainSessionId,
                                           String userText, Boolean businessProbeThisTurn) {
        String key = resolveKey(callRecordId, trainSessionId);
        if (key == null) {
            return HangupDecision.none(0, 0);
        }
        ForcedHangupSession s = ensureSession(key, null);
        int elapsed = elapsedFrom(s);

        if (ForcedHangupRules.isCallDurationExceeded(elapsed)) {
            log.info("[挂断] 通话超时 {}s uuidKey={}", elapsed, key);
            return forceWithWords(HangupType.FORCE_DURATION, elapsed);
        }
        if (StringUtils.hasText(userText) && ForcedHangupRules.isAbuseVulgarOrComplaint(userText)) {
            log.info("[挂断] 辱骂/投诉 uuidKey={} text={}", key, userText);
            return forceWithWords(HangupType.FORCE_ABUSE, elapsed);
        }
        // 仅强硬勿扰挂机；「不用了/没需求/没有」等软拒交给主线挽回，禁止一上来挂断
        if (StringUtils.hasText(userText) && ForcedHangupRules.isHardNoDisturbance(userText)) {
            log.info("[挂断] 客户强硬勿扰 uuidKey={} text={}", key, userText);
            return forceWithWords(HangupType.FORCE_REFUSE, elapsed);
        }

        saveSession(key, s);
        return HangupDecision.none(elapsed, 0);
    }

    public void afterAiReply(Integer callRecordId, String trainSessionId, String aiReply) {
        String key = resolveKey(callRecordId, trainSessionId);
        if (key == null || !StringUtils.hasText(aiReply)) {
            return;
        }
        ForcedHangupSession s = ensureSession(key, null);
        saveSession(key, s);
    }

    /** 不再根据模型输出的结束语自动挂断，仅上述三类规则生效 */
    public HangupDecision evaluateAiHangupSignal(String aiReply, int elapsed, int invalidChatRounds) {
        return HangupDecision.none(elapsed, invalidChatRounds);
    }

    public String stripHangupMarkers(String reply) {
        if (!StringUtils.hasText(reply)) {
            return reply == null ? "" : reply;
        }
        String s = AI_HANGUP.matcher(reply).replaceAll("").trim();
        if (s.isEmpty()) {
            return "";
        }
        if (s.contains(ForcedHangupRules.END_WORDS) || s.contains(ForcedHangupRules.DURATION_END_WORDS)) {
            return "";
        }
        return s;
    }

    public String getFsUuid(Integer callRecordId, String trainSessionId) {
        ForcedHangupSession s = loadSession(resolveKey(callRecordId, trainSessionId));
        return s != null ? s.getFsUuid() : null;
    }

    public String resolveKey(Integer callRecordId, String trainSessionId) {
        if (callRecordId != null) {
            return KEY_PREFIX + callRecordId;
        }
        if (StringUtils.hasText(trainSessionId)) {
            return KEY_PREFIX + "train:" + trainSessionId.trim();
        }
        return null;
    }

    private HangupDecision forceWithWords(String hangupType, int elapsed) {
        HangupDecision d = HangupDecision.force(hangupType, false, elapsed, 0);
        d.setEndWords(ForcedHangupRules.endWordsFor(hangupType));
        return d;
    }

    private ForcedHangupSession ensureSession(String key, String fsUuid) {
        ForcedHangupSession s = loadSession(key);
        if (s == null) {
            ForcedHangupSession created = new ForcedHangupSession();
            created.setConnectedAtEpochSec(System.currentTimeMillis() / 1000);
            created.setInvalidChatRounds(0);
            created.setProbeFailures(0);
            created.setFsUuid(fsUuid);
            saveSession(key, created);
            s = created;
        }
        return s;
    }

    private int elapsedFrom(ForcedHangupSession s) {
        if (s == null || s.getConnectedAtEpochSec() <= 0) {
            return 0;
        }
        return (int) (System.currentTimeMillis() / 1000 - s.getConnectedAtEpochSec());
    }

    private ForcedHangupSession loadSession(String key) {
        if (key == null) {
            return null;
        }
        try {
            String json = redisTemplate.opsForValue().get(key);
            if (!StringUtils.hasText(json)) {
                return null;
            }
            return objectMapper.readValue(json, ForcedHangupSession.class);
        } catch (Exception e) {
            log.warn("读取挂断会话状态失败 key={}: {}", key, e.getMessage());
            return null;
        }
    }

    private void saveSession(String key, ForcedHangupSession s) {
        try {
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(s), TTL);
        } catch (Exception e) {
            log.warn("保存挂断会话状态失败 key={}: {}", key, e.getMessage());
        }
    }
}
