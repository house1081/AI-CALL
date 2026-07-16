package com.aicall.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 严格轮次：用户占线时 AI 必须闭嘴；用户句末静默满门槛后 AI 才允许说一轮；用户插嘴则 AI 再次闭嘴。
 */
@Slf4j
@Component
public class DialogTurnRegistry {

    public enum ActiveSpeaker {
        /** 等待/静默期，尚未轮到 AI */
        NONE,
        /** 用户正在说或刚说 — AI 禁止播报/生成 */
        USER,
        /** AI 正在播报 */
        AI
    }

    private final ConcurrentHashMap<String, TurnState> turns = new ConcurrentHashMap<>();

    public void register(String uuid) {
        if (!StringUtils.hasText(uuid)) {
            return;
        }
        turns.put(uuid.trim(), new TurnState());
        log.info("[轮次] 注册 uuid={} 规则=用户先说→AI后说→用户插嘴AI闭嘴", uuid);
    }

    public void unregister(String uuid) {
        if (StringUtils.hasText(uuid)) {
            turns.remove(uuid.trim());
        }
    }

    public ActiveSpeaker getSpeaker(String uuid) {
        TurnState s = state(uuid);
        return s != null ? s.activeSpeaker : ActiveSpeaker.NONE;
    }

    /** 检测到用户说话：立即夺权，AI 必须停止 */
    public void userTakesFloor(String uuid, String reason) {
        TurnState s = require(uuid);
        ActiveSpeaker prev = s.activeSpeaker;
        s.activeSpeaker = ActiveSpeaker.USER;
        s.userSpeaking = true;
        s.userFloorSinceMs = System.currentTimeMillis();
        s.userSpeechStoppedAt = 0;
        s.userTurnReady = false;
        s.aiSpokeThisUserTurn = false;
        if (prev != ActiveSpeaker.USER) {
            log.info("[轮次] USER 占线 uuid={} reason={} prev={} → AI 闭嘴", uuid, reason, prev);
        }
    }

    /** VAD 句末：用户说完，开始计静默 */
    public void userStoppedSpeaking(String uuid) {
        TurnState s = require(uuid);
        s.userSpeaking = false;
        long now = System.currentTimeMillis();
        s.userSpeechStoppedAt = now;
        s.userTurnReady = false;
        log.debug("[轮次] USER 句末 uuid={} 等待静默后 AI 才可说", uuid);
    }

    /** 静默满门槛：允许 AI 应答一轮 */
    public void markUserTurnReady(String uuid, int silenceMs) {
        TurnState s = require(uuid);
        if (s.userSpeaking || s.userSpeechStoppedAt <= 0) {
            return;
        }
        long elapsed = System.currentTimeMillis() - s.userSpeechStoppedAt;
        if (elapsed < silenceMs) {
            return;
        }
        if (s.userTurnReady) {
            return;
        }
        s.userTurnReady = true;
        s.activeSpeaker = ActiveSpeaker.NONE;
        log.info("[轮次] USER 轮次完成 uuid={} 静默{}ms → 允许 AI 说一轮", uuid, elapsed);
    }

    public boolean isUserTurnReady(String uuid) {
        TurnState s = state(uuid);
        return s != null && s.userTurnReady && !s.userSpeaking && !s.aiSpokeThisUserTurn;
    }

    public boolean isUserSpeaking(String uuid) {
        TurnState s = state(uuid);
        return s != null && (s.userSpeaking || s.activeSpeaker == ActiveSpeaker.USER);
    }

    /** AI 开始播报/生成前检查 */
    public boolean mayAiSpeak(String uuid) {
        TurnState s = state(uuid);
        if (s == null) {
            return true;
        }
        if (s.userSpeaking || s.activeSpeaker == ActiveSpeaker.USER) {
            return false;
        }
        return s.userTurnReady && !s.aiSpokeThisUserTurn;
    }

    /** VAD 确认用户句末：建立「用户说完→AI开口」耗时锚点（比 ASR 后估算更准） */
    public void markUserUtteranceEnded(String uuid, int trailingSilenceMs) {
        if (!StringUtils.hasText(uuid)) {
            return;
        }
        TurnState s = require(uuid);
        long now = System.currentTimeMillis();
        s.latencyAnchorMs = now - Math.max(0, trailingSilenceMs);
        s.userSpeechStoppedAt = now;
        s.listenPhaseMs = 0;
        s.asrPhaseMs = 0;
        s.matchPhaseMs = 0;
        log.debug("[耗时] uuid={} 用户句末锚点 trailingSilence={}ms", uuid.trim(), trailingSilenceMs);
    }

    public void markListenPhaseMs(String uuid, long listenMs) {
        TurnState s = state(uuid);
        if (s != null) {
            s.listenPhaseMs = listenMs;
        }
    }

    public void markAsrPhaseMs(String uuid, long asrOnlyMs) {
        TurnState s = state(uuid);
        if (s != null) {
            s.asrPhaseMs = asrOnlyMs;
        }
    }

    public void markMatchPhaseMs(String uuid, long matchMs) {
        TurnState s = state(uuid);
        if (s != null) {
            s.matchPhaseMs = matchMs;
        }
    }

    /** AI 音频已下发 FS（客户即将/已经听到），记录真实开口延迟 */
    public void markAiPlaybackStarted(String uuid, String source) {
        TurnState s = state(uuid);
        if (s == null || s.latencyAnchorMs <= 0) {
            return;
        }
        long total = System.currentTimeMillis() - s.latencyAnchorMs;
        if (s.matchPhaseStartMs > 0) {
            s.matchPhaseMs = System.currentTimeMillis() - s.matchPhaseStartMs;
        }
        log.info("[耗时] 用户句末→AI开播 uuid={} {}ms source={} (听音{}+ASR{}+匹配{}ms)",
                uuid.trim(), total, source, s.listenPhaseMs, s.asrPhaseMs, s.matchPhaseMs);
        s.latencyAnchorMs = 0;
    }
    /** 分段模式：VAD 已确认用户整句说完 */
    public void forceUserTurnReady(String uuid, int userSilenceAlreadyMs) {
        TurnState s = require(uuid);
        s.userSpeaking = false;
        long now = System.currentTimeMillis();
        s.userSpeechStoppedAt = now;
        if (s.latencyAnchorMs <= 0) {
            s.latencyAnchorMs = now - Math.max(0, userSilenceAlreadyMs);
        }
        s.userTurnReady = true;
        s.activeSpeaker = ActiveSpeaker.NONE;
        s.aiSpokeThisUserTurn = false;
        log.info("[轮次] USER 整句说完 uuid={} → 允许 AI 说一轮", uuid);
    }

    public void forceUserTurnReady(String uuid) {
        forceUserTurnReady(uuid, 0);
    }

    public void aiTakesFloor(String uuid) {
        TurnState s = require(uuid);
        long now = System.currentTimeMillis();
        long anchor = s.latencyAnchorMs > 0 ? s.latencyAnchorMs : s.userSpeechStoppedAt;
        if (anchor > 0) {
            log.info("[耗时] uuid={} 开始应答处理 +{}ms（尚未开播）", uuid.trim(), now - anchor);
        }
        s.activeSpeaker = ActiveSpeaker.AI;
        s.aiFloorSinceMs = now;
        s.aiSpokeThisUserTurn = true;
        s.userTurnReady = false;
        s.matchPhaseStartMs = now;
        log.info("[轮次] AI 占线 uuid={} → 播报中，用户说话则立即让出", uuid);
    }

    public void aiYieldsFloor(String uuid) {
        TurnState s = state(uuid);
        if (s == null) {
            return;
        }
        if (s.activeSpeaker == ActiveSpeaker.AI) {
            s.activeSpeaker = ActiveSpeaker.NONE;
            log.info("[轮次] AI 说完 uuid={} → 等待用户", uuid);
        }
    }

    /** 开场白播完：进入听用户 */
    public void afterOpeningPlayback(String uuid) {
        TurnState s = require(uuid);
        s.activeSpeaker = ActiveSpeaker.NONE;
        s.userSpeaking = false;
        s.userTurnReady = false;
        s.userSpeechStoppedAt = 0;
        s.aiSpokeThisUserTurn = false;
        log.info("[轮次] 开场白结束 uuid={} → 等待 USER 先说话", uuid);
    }

    private TurnState require(String uuid) {
        TurnState s = state(uuid);
        if (s == null) {
            s = new TurnState();
            turns.put(uuid.trim(), s);
        }
        return s;
    }

    private TurnState state(String uuid) {
        if (!StringUtils.hasText(uuid)) {
            return null;
        }
        return turns.get(uuid.trim());
    }

    private static final class TurnState {
        volatile ActiveSpeaker activeSpeaker = ActiveSpeaker.NONE;
        volatile boolean userSpeaking;
        volatile boolean userTurnReady;
        volatile boolean aiSpokeThisUserTurn;
        volatile long userFloorSinceMs;
        volatile long userSpeechStoppedAt;
        volatile long aiFloorSinceMs;
        /** 用于 [耗时] 用户句末→AI开播 */
        volatile long latencyAnchorMs;
        volatile long listenPhaseMs;
        volatile long asrPhaseMs;
        volatile long matchPhaseMs;
        volatile long matchPhaseStartMs;
    }
}
