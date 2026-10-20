package com.aicall.service;

import com.aicall.common.CallMode;
import com.aicall.common.CallStatus;
import com.aicall.common.HangupType;
import com.aicall.config.FreeSwitchProperties;
import com.aicall.dto.SensitiveWordMatch;
import com.aicall.entity.CallRecord;
import com.aicall.entity.RiskConfig;
import com.aicall.entity.RiskLog;
import com.aicall.entity.TenantAgent;
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
    private static final String KEY_TRANSFER_AGENT = "call:human:agent:";

    private final SensitiveWordMonitorService sensitiveWordMonitorService;
    private final RiskControlService riskControlService;
    private final FreeSwitchProperties freeSwitchProperties;
    private final FreeSwitchEslService eslService;
    private final VoicePlaybackService voicePlaybackService;
    private final CallAiVoiceService callAiVoiceService;
    private final RecordingOnlyPlaybackService recordingOnlyPlaybackService;
    private final VoiceRuntimeSettingsService voiceRuntimeSettingsService;
    private final CallDialogPersistService callDialogPersistService;
    private final CallSessionService callSessionService;
    private final CallEndSummaryService callEndSummaryService;
    private final CallRecordMapper callRecordMapper;
    private final RiskLogMapper riskLogMapper;
    private final StringRedisTemplate redisTemplate;
    private final AgentRoutingService agentRoutingService;

    private final ExecutorService watchExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "human-transfer-watch");
        t.setDaemon(true);
        return t;
    });

    /** 预录外呼冷门问题转专业顾问（复用 FS bridge，无需敏感词命中） */
    public boolean triggerAdvisorTransfer(String uuid, Integer callRecordId, String phone, String reason) {
        if (!freeSwitchProperties.isEnabled() || !StringUtils.hasText(uuid) || callRecordId == null) {
            return false;
        }
        RiskConfig cfg = riskControlService.config();
        TransferTarget target = resolveTransferTarget(callRecordId, cfg);
        if (target == null || !StringUtils.hasText(target.dest)) {
            log.warn("[转顾问] 未配置转人工目标，请配置坐席或风控 FS 转接目标");
            callDialogPersistService.appendSystem(callRecordId, "转顾问失败：未配置坐席转接目标");
            return false;
        }
        if (cfg.getHumanTransferEnabled() == null || cfg.getHumanTransferEnabled() != 1) {
            // 有可用坐席时仍允许顾问转接；仅无坐席且未开全局转人工时拒绝
            if (target.agentId == null) {
                log.warn("[转顾问] 未启用转人工且无空闲坐席");
                return false;
            }
        }
        String lockKey = KEY_TRANSFERRED + callRecordId;
        if (!Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(lockKey, uuid, Duration.ofHours(4)))) {
            return true;
        }
        try {
            log.info("[转顾问] 预录冷门转人工 uuid={} recordId={} reason={} dest={}",
                    uuid, callRecordId, reason, target.dest);
            callDialogPersistService.appendSystem(callRecordId, "冷门问题转专业信贷顾问");
            markHangupType(callRecordId, target.agentId);
            voicePlaybackService.stopChannelPlayback(uuid);
            if (target.agentId != null) {
                agentRoutingService.markBusy(target.agentId);
                redisTemplate.opsForValue().set(KEY_TRANSFER_AGENT + callRecordId,
                        String.valueOf(target.agentId), Duration.ofHours(4));
            }
            boolean ok = bridgeToAgent(uuid, target.dest);
            if (!ok) {
                callDialogPersistService.appendSystem(callRecordId, "转顾问失败，请检查坐席/FS 转接目标");
                redisTemplate.delete(lockKey);
                releaseTransferAgent(callRecordId);
                return false;
            }
            callDialogPersistService.appendSystem(callRecordId, "已转接专业信贷顾问");
            return true;
        } catch (Exception e) {
            log.error("[转顾问] 异常 uuid={} recordId={}: {}", uuid, callRecordId, e.getMessage(), e);
            redisTemplate.delete(lockKey);
            releaseTransferAgent(callRecordId);
            return false;
        }
    }

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
        TransferTarget target = resolveTransferTarget(callRecordId, cfg);
        try {
            log.warn("[风控] 关键词命中转人工 uuid={} recordId={} phone={} {}={} text={} dest={}",
                    uuid, callRecordId, phone, match.getWordTypeLabel(), match.getWord(), userText,
                    target != null ? target.dest : null);

            if (target == null || !StringUtils.hasText(target.dest)) {
                callDialogPersistService.appendSystem(callRecordId, "转人工失败：无空闲坐席且未配置全局转接目标");
                redisTemplate.delete(lockKey);
                return false;
            }

            callDialogPersistService.appendSystem(callRecordId,
                    "检测到" + match.getWordTypeLabel() + "「" + match.getWord() + "」，正在转人工");
            markHangupType(callRecordId, target.agentId);

            voicePlaybackService.stopChannelPlayback(uuid);

            if (!playTransferPrompt(uuid, cfg)) {
                log.warn("[转人工] 转接提示音播放失败 uuid={}", uuid);
            }

            if (target.agentId != null) {
                agentRoutingService.markBusy(target.agentId);
                redisTemplate.opsForValue().set(KEY_TRANSFER_AGENT + callRecordId,
                        String.valueOf(target.agentId), Duration.ofHours(4));
            }

            boolean ok = bridgeToAgent(uuid, target.dest);
            if (!ok) {
                log.error("[转人工] FS bridge 失败 uuid={} dest={}", uuid, target.dest);
                callDialogPersistService.appendSystem(callRecordId, "转人工失败，请检查坐席/FS 转接目标配置");
                redisTemplate.delete(lockKey);
                releaseTransferAgent(callRecordId);
                return false;
            }

            callDialogPersistService.appendSystem(callRecordId, "已转接人工坐席");
            writeRiskLog(phone, match, userText);
            return true;
        } catch (Exception e) {
            log.error("[转人工] 异常 uuid={} recordId={}: {}", uuid, callRecordId, e.getMessage(), e);
            redisTemplate.delete(lockKey);
            releaseTransferAgent(callRecordId);
            return false;
        }
    }

    private TransferTarget resolveTransferTarget(Integer callRecordId, RiskConfig cfg) {
        CallRecord record = callRecordMapper.selectById(callRecordId);
        Integer tenantId = record != null ? record.getTenantId() : null;
        Integer lineId = record != null ? record.getLineId() : null;
        TenantAgent agent = agentRoutingService.pickAvailable(tenantId);
        if (agent != null) {
            try {
                String dest = agentRoutingService.resolveFsDialDest(agent, lineId);
                if (StringUtils.hasText(dest)) {
                    TransferTarget t = new TransferTarget();
                    t.agentId = agent.getId();
                    t.dest = dest;
                    return t;
                }
            } catch (Exception e) {
                log.warn("[转人工] 解析坐席目标失败 agentId={}: {}", agent.getId(), e.getMessage());
            }
        }
        if (cfg != null && StringUtils.hasText(cfg.getHumanTransferDest())) {
            TransferTarget t = new TransferTarget();
            t.dest = cfg.getHumanTransferDest().trim();
            return t;
        }
        return null;
    }

    private void releaseTransferAgent(Integer callRecordId) {
        if (callRecordId == null) {
            return;
        }
        String key = KEY_TRANSFER_AGENT + callRecordId;
        String id = redisTemplate.opsForValue().get(key);
        redisTemplate.delete(key);
        if (StringUtils.hasText(id)) {
            try {
                agentRoutingService.releaseAfterCall(Integer.parseInt(id));
            } catch (NumberFormatException ignore) {
            }
        }
    }

    private static final class TransferTarget {
        Integer agentId;
        String dest;
    }

    private boolean playTransferPrompt(String uuid, RiskConfig cfg) {
        if (!eslService.uuidExists(uuid)) {
            return false;
        }
        String prompt = StringUtils.hasText(cfg.getHumanTransferPrompt())
                ? cfg.getHumanTransferPrompt().trim()
                : "我马上为您转接人工坐席，请稍等";
        if (!StringUtils.hasText(prompt)) {
            return false;
        }
        try {
            if (recordingOnlyPlaybackService.playCachedPhrase(uuid, prompt, null)) {
                return true;
            }
        } catch (Exception e) {
            log.debug("[转人工] 转接提示录音不可用 uuid={}: {}", uuid, e.getMessage());
        }
        if (voiceRuntimeSettingsService.isSmartPrerecordMode()) {
            log.warn("[转人工] 智能预录无转接提示录音 uuid={}", uuid);
            return false;
        }
        try {
            callAiVoiceService.playText(uuid, prompt);
            voicePlaybackService.waitPlaybackFinished(uuid, prompt);
            return true;
        } catch (Exception e) {
            log.debug("[转人工] TTS 转接提示失败 uuid={}: {}", uuid, e.getMessage());
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
        } finally {
            releaseTransferAgent(callRecordId);
        }
    }

    private void markHangupType(Integer callRecordId, Integer agentId) {
        CallRecord upd = new CallRecord();
        upd.setId(callRecordId);
        upd.setHangupType(HangupType.TRANSFER_HUMAN);
        upd.setCallMode(CallMode.HUMAN_TRANSFER);
        if (agentId != null) {
            upd.setAgentId(agentId);
        }
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
