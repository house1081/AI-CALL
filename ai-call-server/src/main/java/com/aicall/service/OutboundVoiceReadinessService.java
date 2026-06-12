package com.aicall.service;

import com.aicall.config.AiVoiceProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 外呼前等待 RAG 索引、ASR 预热、TTS 话术预热完成，避免首通与冷启动争抢。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OutboundVoiceReadinessService {

    private final AiVoiceProperties aiVoiceProperties;
    private final DialogRagRetrievalService dialogRagRetrievalService;
    private final AsrWarmupService asrWarmupService;
    private final TtsPhraseCacheService ttsPhraseCacheService;

    public boolean isReady() {
        if (!aiVoiceProperties.isOutboundReadyGateEnabled()) {
            return true;
        }
        if (!dialogRagRetrievalService.isIndexReady()) {
            return false;
        }
        if (aiVoiceProperties.isAsrWarmOnStartup() && !asrWarmupService.isWarmComplete()) {
            return false;
        }
        if (aiVoiceProperties.isTtsPhraseWarmOnStartup() && !ttsPhraseCacheService.isPhraseWarmComplete()) {
            return false;
        }
        return true;
    }

    public void awaitReady() {
        if (!aiVoiceProperties.isOutboundReadyGateEnabled()) {
            return;
        }
        long maxWait = Math.max(5_000L, aiVoiceProperties.getOutboundReadyMaxWaitMs());
        long deadline = System.currentTimeMillis() + maxWait;
        while (!isReady() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (isReady()) {
            log.info("[外呼就绪] RAG+ASR+TTS 预热已完成，允许拨号");
        } else {
            log.warn("[外呼就绪] 等待超时 {}ms，仍缺少: {}", maxWait, pendingParts());
        }
    }

    private String pendingParts() {
        StringBuilder sb = new StringBuilder();
        if (!dialogRagRetrievalService.isIndexReady()) {
            sb.append("RAG ");
        }
        if (aiVoiceProperties.isAsrWarmOnStartup() && !asrWarmupService.isWarmComplete()) {
            sb.append("ASR ");
        }
        if (aiVoiceProperties.isTtsPhraseWarmOnStartup() && !ttsPhraseCacheService.isPhraseWarmComplete()) {
            sb.append("TTS ");
        }
        return sb.toString().trim();
    }
}
