package com.aicall.service;

import com.aicall.config.AiVoiceProperties;
import com.aicall.config.FreeSwitchProperties;
import com.aicall.entity.CallRecord;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * originate 成功后轮询 FS 通道是否接通，自动建会话并播报开场白（无需 FS 调 call-start）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OutboundAnswerVoiceService {

    private static final String KEY_HANDLED = "fs:voice:handled:";
    private static final Duration HANDLED_TTL = Duration.ofHours(4);

    private final AiVoiceProperties aiVoiceProperties;
    private final FreeSwitchProperties freeSwitchProperties;
    private final FreeSwitchEslService eslService;
    private final CallSessionService callSessionService;
    private final StringRedisTemplate redisTemplate;
    private final OutboundDialogLoopService outboundDialogLoopService;
    private final OllamaChatService ollamaChatService;
    private final OpeningVoicePrewarmService openingVoicePrewarmService;
    private final OutboundNoAnswerService outboundNoAnswerService;

    private final ExecutorService watchExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "outbound-answer-voice");
        t.setDaemon(true);
        return t;
    });

    /** 独立线程池，避免监听线程与对话循环争抢/耗尽 CachedThreadPool */
    private final ExecutorService dialogExecutor = Executors.newFixedThreadPool(8, r -> {
        Thread t = new Thread(r, "dialog-loop");
        t.setDaemon(true);
        return t;
    });

    public void scheduleAfterOriginate(String fsUuid, FreeSwitchDialService.DialRequest req) {
        if (!aiVoiceProperties.isEnabled()
                || !aiVoiceProperties.isAutoPlayAfterOriginate()
                || !freeSwitchProperties.isEnabled()
                || !StringUtils.hasText(fsUuid)
                || req == null) {
            return;
        }
        if (aiVoiceProperties.isOpeningVoicePrecacheEnabled()) {
            try {
                openingVoicePrewarmService.schedule(fsUuid, resolveOpeningText());
            } catch (Exception e) {
                log.trace("振铃期开场白预热跳过 uuid={}: {}", fsUuid, e.getMessage());
            }
        }
        watchExecutor.submit(() -> watchAndPlay(fsUuid, req));
    }

    private void watchAndPlay(String fsUuid, FreeSwitchDialService.DialRequest req) {
        int maxRings = resolveMaxRingCount(req);
        int ringCycleSec = Math.max(2, aiVoiceProperties.getAnswerRingCycleSec());
        int ringWatchSec = maxRings * ringCycleSec + 8;
        int maxSec = Math.max(ringWatchSec, Math.max(10, aiVoiceProperties.getAnswerWatchSeconds()));
        log.info("开始监听接通并播报 uuid={} phone={} maxSec={} maxRings={} ringCycleSec={}",
                fsUuid, req.getCallee(), maxSec, maxRings, ringCycleSec);
        ChannelSnapshot last = null;
        boolean everExisted = false;
        int ringCount = 0;
        long lastRingTickMs = 0L;
        int pollMs = Math.max(300, aiVoiceProperties.getAnswerWatchPollMs());
        for (int i = 0; i < maxSec; i++) {
            try {
                Thread.sleep(i == 0 ? 150L : pollMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (!eslService.uuidExists(fsUuid)) {
                logChannelEnded(fsUuid, req, i, everExisted, last);
                if (everExisted && !isAlreadyHandled(fsUuid)) {
                    handleNoAnswer(fsUuid, req, "通道已结束未摘机");
                }
                return;
            }
            everExisted = true;
            last = snapshotChannel(fsUuid);
            if (!eslService.isChannelAnswered(fsUuid)) {
                if (i >= 12 && i % 10 == 0 && isAllUndefSnapshot(last)) {
                    log.warn("摘机检测仍无有效通道变量 uuid={} phone={} sec={}，尝试 uuid_dump 诊断",
                            fsUuid, req.getCallee(), i);
                    log.info("uuid_dump uuid={} keys={}", fsUuid, eslService.uuidDumpVars(fsUuid).keySet());
                }
                if (isRinging(last)) {
                    long now = System.currentTimeMillis();
                    if (lastRingTickMs == 0L || now - lastRingTickMs >= ringCycleSec * 1000L) {
                        ringCount++;
                        lastRingTickMs = now;
                        log.info("振铃计数 uuid={} phone={} ring={}/{}", fsUuid, req.getCallee(), ringCount, maxRings);
                        if (ringCount >= maxRings) {
                            log.warn("[任务] 振铃{}次无人接听 uuid={} phone={}", ringCount, fsUuid, req.getCallee());
                            handleNoAnswer(fsUuid, req, "振铃" + ringCount + "次无人接听");
                            return;
                        }
                    }
                }
                if (i > 0 && i % 5 == 0) {
                    log.info("等待客户摘机 uuid={} phone={} sec={} rings={}/{} state={} disposition={}",
                            fsUuid, req.getCallee(), i, ringCount, maxRings,
                            last != null ? last.channelState() : "-",
                            last != null ? last.endpointDisposition() : "-");
                }
                continue;
            }
            String answerEpoch = eslService.uuidGetVar(fsUuid, "answer_epoch");
            log.info("检测到客户已摘机 uuid={} phone={} 耗时约{}s answer_epoch={} disposition={}",
                    fsUuid, req.getCallee(), i, answerEpoch,
                    last != null ? last.endpointDisposition() : "-");
            if (Boolean.TRUE.equals(redisTemplate.hasKey(KEY_HANDLED + fsUuid))) {
                return;
            }
            redisTemplate.opsForValue().set(KEY_HANDLED + fsUuid, "1", HANDLED_TTL);
            try {
                eslService.ensureOutboundMediaReady(fsUuid);
                String openingText = resolveOpeningText();
                openingVoicePrewarmService.schedule(fsUuid, openingText);
                int delay = Math.max(0, aiVoiceProperties.getAnswerPlayDelayMs());
                if (delay > 0) {
                    Thread.sleep(delay);
                }
                CallSessionService.StartReq start = new CallSessionService.StartReq();
                start.setFsUuid(fsUuid);
                start.setTenantId(req.getTenantId());
                start.setLineId(req.getLineId());
                start.setTaskId(req.getTaskId());
                start.setCustomerId(req.getCustomerId());
                start.setCustomerPhone(req.getCallee());
                if (aiVoiceProperties.isDialogEnabled()) {
                    start.setSkipOpeningVoice(true);
                }
                CallRecord record = callSessionService.startSession(start);
                log.info("接通自动建会话 uuid={} callRecordId={} dialog={}",
                        fsUuid, record.getId(), aiVoiceProperties.isDialogEnabled());
                if (aiVoiceProperties.isDialogEnabled()) {
                    Integer recordId = record.getId();
                    log.info("[对话] 提交后台循环 uuid={} recordId={}", fsUuid, recordId);
                    dialogExecutor.submit(() -> {
                        try {
                            outboundDialogLoopService.run(fsUuid, recordId);
                        } catch (Exception ex) {
                            log.warn("[对话] 后台循环异常 uuid={} recordId={}: {}",
                                    fsUuid, recordId, ex.getMessage(), ex);
                        }
                    });
                }
            } catch (Exception e) {
                log.warn("接通自动播报失败 uuid={}: {}", fsUuid, e.getMessage());
                redisTemplate.delete(KEY_HANDLED + fsUuid);
            }
            return;
        }
        log.warn("监听超时未检测到接通 uuid={} phone={} rings={}/{} last={}",
                fsUuid, req.getCallee(), ringCount, maxRings, last);
        if (!isAlreadyHandled(fsUuid)) {
            handleNoAnswer(fsUuid, req, "监听超时未摘机");
        }
    }

    private int resolveMaxRingCount(FreeSwitchDialService.DialRequest req) {
        if (req != null && req.getMaxRingCount() != null && req.getMaxRingCount() > 0) {
            return req.getMaxRingCount();
        }
        return Math.max(3, aiVoiceProperties.getAnswerMaxRingCount());
    }

    private boolean isAlreadyHandled(String fsUuid) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(KEY_HANDLED + fsUuid));
    }

    private void handleNoAnswer(String fsUuid, FreeSwitchDialService.DialRequest req, String reason) {
        if (isAlreadyHandled(fsUuid)) {
            return;
        }
        String guardKey = "fs:noanswer:handled:" + fsUuid;
        if (Boolean.TRUE.equals(redisTemplate.hasKey(guardKey))) {
            return;
        }
        redisTemplate.opsForValue().set(guardKey, "1", HANDLED_TTL);
        outboundNoAnswerService.onNoAnswer(fsUuid, req != null ? req.getTaskId() : null,
                req != null ? req.getCallee() : null, reason);
    }

    private static boolean isRinging(ChannelSnapshot s) {
        if (s == null) {
            return false;
        }
        String state = s.channelState();
        if (StringUtils.hasText(state) && !"_undef_".equals(state)) {
            String u = state.toUpperCase();
            if (u.contains("RING") || u.contains("EARLY")) {
                return true;
            }
        }
        String disp = s.endpointDisposition();
        if (StringUtils.hasText(disp) && !"_undef_".equals(disp)) {
            String u = disp.toUpperCase();
            if (u.contains("RING") || u.contains("EARLY") || u.contains("PROGRESS")) {
                return true;
            }
        }
        return false;
    }

    private ChannelSnapshot snapshotChannel(String fsUuid) {
        String state = eslService.uuidGetVar(fsUuid, "channel_state");
        String disp = eslService.uuidGetVar(fsUuid, "endpoint_disposition");
        if (isUndef(state) && isUndef(disp)) {
            var dump = eslService.uuidDumpVars(fsUuid);
            state = firstDump(dump, "Channel-State", "channel_state", "state");
            disp = firstDump(dump, "endpoint_disposition", "variable_endpoint_disposition");
        }
        return new ChannelSnapshot(
                state,
                disp,
                eslService.uuidGetVar(fsUuid, "hangup_cause"),
                eslService.uuidGetVar(fsUuid, "originate_disposition"),
                eslService.uuidGetVar(fsUuid, "sip_invite_failure_status"),
                eslService.uuidGetVar(fsUuid, "sip_invite_failure_phrase"),
                eslService.uuidGetVar(fsUuid, "proto_specific_hangup_cause"));
    }

    private static boolean isUndef(String v) {
        return !StringUtils.hasText(v) || "_undef_".equals(v.trim());
    }

    private static boolean isAllUndefSnapshot(ChannelSnapshot s) {
        if (s == null) {
            return true;
        }
        return isUndef(s.channelState()) && isUndef(s.endpointDisposition());
    }

    private static String firstDump(java.util.Map<String, String> dump, String... keys) {
        if (dump == null) {
            return "_undef_";
        }
        for (String k : keys) {
            String v = dump.get(k);
            if (StringUtils.hasText(v) && !"_undef_".equals(v)) {
                return v;
            }
        }
        return "_undef_";
    }

    private void logChannelEnded(String fsUuid, FreeSwitchDialService.DialRequest req, int elapsedSec,
                                 boolean everExisted, ChannelSnapshot last) {
        String phone = req != null ? req.getCallee() : null;
        if (!everExisted) {
            log.warn("通道已结束 uuid={} phone={} 约{}s：FS 上未找到该 uuid（originate 可能失败或未使用 origination_uuid）",
                    fsUuid, phone, elapsedSec);
            return;
        }
        if (elapsedSec < 5) {
            log.warn("通道已结束 uuid={} phone={} 约{}s 内挂断 state={} disposition={} hangup={} "
                            + "originate={} sip={} {}",
                    fsUuid, phone, elapsedSec,
                    v(last, ChannelSnapshot::channelState),
                    v(last, ChannelSnapshot::endpointDisposition),
                    v(last, ChannelSnapshot::hangupCause),
                    v(last, ChannelSnapshot::originateDisposition),
                    v(last, ChannelSnapshot::sipFailureStatus),
                    v(last, ChannelSnapshot::sipFailurePhrase));
        } else {
            log.info("通道已结束，停止监听 uuid={} phone={}", fsUuid, phone);
        }
    }

    private String resolveOpeningText() {
        try {
            String text = ollamaChatService.activePrompt().getOpeningRemarks();
            if (StringUtils.hasText(text)) {
                return text.trim();
            }
        } catch (Exception e) {
            log.debug("读取开场白话术失败: {}", e.getMessage());
        }
        return "您好，我是智能外呼助手，很高兴为您服务。";
    }

    private static String v(ChannelSnapshot s, java.util.function.Function<ChannelSnapshot, String> f) {
        if (s == null) {
            return "-";
        }
        String x = f.apply(s);
        return StringUtils.hasText(x) && !"_undef_".equals(x) ? x : "-";
    }

    private record ChannelSnapshot(
            String channelState,
            String endpointDisposition,
            String hangupCause,
            String originateDisposition,
            String sipFailureStatus,
            String sipFailurePhrase,
            String protoHangupCause) {
    }
}
