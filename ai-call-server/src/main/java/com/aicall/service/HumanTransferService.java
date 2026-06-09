package com.aicall.service;

import com.aicall.common.CallStatus;
import com.aicall.common.HangupType;
import com.aicall.config.FreeSwitchProperties;
import com.aicall.dto.SensitiveWordMatch;
import com.aicall.entity.CallRecord;
import com.aicall.entity.RiskConfig;
import com.aicall.entity.RiskLog;
import com.aicall.mapper.CallRecordMapper;
import com.aicall.mapper.RiskLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 命中敏感词后转人工：停止 AI 对话，FS bridge 坐席，通话保持不断。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HumanTransferService {

    private static final String KEY_TRANSFERRED = "call:human:transferred:";

    private final SensitiveWordMonitorService sensitiveWordMonitorService;
    private final RiskControlService riskControlService;
    private final FreeSwitchProperties freeSwitchProperties;
    private final FreeSwitchEslService eslService;
    private final VoicePlaybackService voicePlaybackService;
    private final CallAiVoiceService callAiVoiceService;
    private final CallDialogPersistService callDialogPersistService;
    private final CallSessionService callSessionService;
    private final CallEndSummaryService callEndSummaryService;
    private final CallRecordMapper callRecordMapper;
    private final RiskLogMapper riskLogMapper;
    private final StringRedisTemplate redisTemplate;

    private final ExecutorService watchExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "human-transfer-watch");
        t.setDaemon(true);
        return t;
    });

    /**
     * 检测客户话术，命中则发起转人工。
     *
     * @return true 表示已触发转人工，对话环应退出且勿挂断客户通道
     */
    public boolean onUserSpeech(String uuid, Integer callRecordId, String phone, String userText) {
        if (!freeSwitchProperties.isEnabled() || !StringUtils.hasText(uuid) || callRecordId == null) {
            return false;
        }
        if (!sensitiveWordMonitorService.isMonitorEnabled() || !sensitiveWordMonitorService.isTransferEnabled()) {
            return false;
        }
        SensitiveWordMatch match = sensitiveWordMonitorService.match(userText);
        if (match == null) {
            return false;
        }
        return triggerTransfer(uuid, callRecordId, phone, userText, match);
    }

    public boolean isTransferred(Integer callRecordId) {
        if (callRecordId == null) {
            return false;
        }
        return Boolean.TRUE.equals(redisTemplate.hasKey(KEY_TRANSFERRED + callRecordId));
    }

    /** 对话环退出后的清理：不 kill 客户通道，后台监听挂机后结算 */
    public void finalizeAiHandoff(String uuid, Integer callRecordId, long callStartMs) {
        if (!isTransferred(callRecordId)) {
            return;
        }
        watchExecutor.submit(() -> watchUntilHangup(uuid, callRecordId, callStartMs));
    }

    private boolean triggerTransfer(String uuid, Integer callRecordId, String phone,
                                    String userText, SensitiveWordMatch match) {
        String lockKey = KEY_TRANSFERRED + callRecordId;
        if (!Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(lockKey, uuid, Duration.ofHours(4)))) {
            return true;
        }
        RiskConfig cfg = riskControlService.config();
        try {
            log.warn("[风控] 关键词命中转人工 uuid={} recordId={} phone={} {}={} text={}",
                    uuid, callRecordId, phone, match.getWordTypeLabel(), match.getWord(), userText);

            callDialogPersistService.appendSystem(callRecordId,
                    "检测到" + match.getWordTypeLabel() + "「" + match.getWord() + "」，正在转人工");
            markHangupType(callRecordId);

            voicePlaybackService.stopChannelPlayback(uuid);

            String prompt = StringUtils.hasText(cfg.getHumanTransferPrompt())
                    ? cfg.getHumanTransferPrompt().trim()
                    : "我马上为您转接人工坐席，请稍等";
            if (StringUtils.hasText(prompt) && eslService.uuidExists(uuid)) {
                callAiVoiceService.playText(uuid, prompt);
                voicePlaybackService.waitPlaybackFinished(uuid, prompt);
            }

            boolean ok = bridgeToAgent(uuid, cfg.getHumanTransferDest().trim());
            if (!ok) {
                log.error("[转人工] FS bridge 失败 uuid={} dest={}", uuid, cfg.getHumanTransferDest());
                callDialogPersistService.appendSystem(callRecordId, "转人工失败，请检查 FS 转接目标配置");
                redisTemplate.delete(lockKey);
                return false;
            }

            callDialogPersistService.appendSystem(callRecordId, "已转接人工坐席");
            writeRiskLog(phone, match, userText);
            return true;
        } catch (Exception e) {
            log.error("[转人工] 异常 uuid={} recordId={}: {}", uuid, callRecordId, e.getMessage(), e);
            redisTemplate.delete(lockKey);
            return false;
        }
    }

    private boolean bridgeToAgent(String customerUuid, String dest) {
        if (!StringUtils.hasText(dest)) {
            return false;
        }
        String bridgeDest = normalizeDest(dest);
        String cmd = "originate {origination_timeout=45,ignore_early_media=true,absolute_codec_string=PCMU}"
                + bridgeDest + " &uuid_bridge(" + customerUuid.trim() + ")";
        log.info("[转人工] ESL originate bridge uuid={} dest={}", customerUuid, bridgeDest);
        FreeSwitchEslService.EslResponse resp = eslService.bgapi(cmd);
        String reply = resp.getReplyText() != null ? resp.getReplyText() : resp.getBody();
        if (reply != null && (reply.contains("+OK") || reply.contains("Job-UUID"))) {
            return true;
        }
        log.warn("[转人工] bgapi 回复异常 reply={}", reply);
        return reply != null && !reply.contains("-ERR");
    }

    private static String normalizeDest(String dest) {
        String d = dest.trim();
        if (d.contains("/") || d.contains("@") || d.startsWith("sofia/") || d.startsWith("user/")) {
            return d;
        }
        return "user/" + d;
    }

    private void watchUntilHangup(String uuid, Integer callRecordId, long callStartMs) {
        int maxSec = 3600;
        for (int i = 0; i < maxSec; i++) {
            try {
                Thread.sleep(1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (eslService.uuidExists(uuid)) {
                continue;
            }
            finishTransferredCall(uuid, callRecordId, callStartMs);
            return;
        }
        log.warn("[转人工] 监听超时 uuid={} recordId={}，强制结算", uuid, callRecordId);
        finishTransferredCall(uuid, callRecordId, callStartMs);
    }

    private void finishTransferredCall(String uuid, Integer callRecordId, long callStartMs) {
        try {
            int duration = (int) Math.max(1, (System.currentTimeMillis() - callStartMs) / 1000);
            CallSessionService.EndReq end = new CallSessionService.EndReq();
            end.setCallRecordId(callRecordId);
            end.setFsUuid(uuid);
            end.setCallDuration(duration);
            end.setCallStatus(CallStatus.CONNECTED);
            end.setHangupType(HangupType.TRANSFER_HUMAN);
            String dialogText = callDialogPersistService.getDialogText(callRecordId);
            end.setDialogText(dialogText);
            callEndSummaryService.fillEndReqFromDialog(end, dialogText, duration);
            callSessionService.endSession(end);
            redisTemplate.delete(KEY_TRANSFERRED + callRecordId);
            log.info("[转人工] 通话已结束并结算 recordId={} duration={}s", callRecordId, duration);
        } catch (Exception e) {
            log.warn("[转人工] 结算失败 recordId={}: {}", callRecordId, e.getMessage());
        }
    }

    private void markHangupType(Integer callRecordId) {
        CallRecord upd = new CallRecord();
        upd.setId(callRecordId);
        upd.setHangupType(HangupType.TRANSFER_HUMAN);
        callRecordMapper.updateById(upd);
    }

    private void writeRiskLog(String phone, SensitiveWordMatch match, String userText) {
        RiskLog row = new RiskLog();
        row.setPhone(phone);
        row.setRiskType("sensitive_transfer");
        row.setRemark(match.getWordTypeLabel() + ":" + match.getWord() + " | " + truncate(userText, 80));
        riskLogMapper.insert(row);
    }

    private static String truncate(String s, int max) {
        if (s == null || s.length() <= max) {
            return s;
        }
        return s.substring(0, max) + "…";
    }
}
