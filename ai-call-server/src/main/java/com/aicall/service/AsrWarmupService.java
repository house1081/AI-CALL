package com.aicall.service;

import com.aicall.config.AiVoiceProperties;
import com.aicall.util.TelephonyWavSamples;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** 启动后预热 ASR，吃掉首通冷启动延迟 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AsrWarmupService {

    private final AiVoiceProperties aiVoiceProperties;
    private final AsrRecognitionService asrRecognitionService;
    private final DialogRagRetrievalService dialogRagRetrievalService;

    private final AtomicBoolean warmComplete = new AtomicBoolean(false);

    @EventListener(ApplicationReadyEvent.class)
    public void warmOnStartup() {
        if (!aiVoiceProperties.isAsrWarmOnStartup()) {
            warmComplete.set(true);
            return;
        }
        CompletableFuture.runAsync(() -> {
            waitForRagIndex();
            Path warmFile = null;
            try {
                warmFile = Files.createTempFile("asr-warm-", ".wav");
                TelephonyWavSamples.writeSilenceWav(warmFile, 8000, 400);
                long t0 = System.currentTimeMillis();
                String warmText = asrRecognitionService.recognize(warmFile);
                log.info("[ASR] 预热完成 耗时={}ms text={}",
                        System.currentTimeMillis() - t0, StringUtils.hasText(warmText) ? warmText : "(空/静音)");
            } catch (Exception e) {
                log.warn("[ASR] 预热失败（外呼仍将进行）: {}", e.getMessage());
            } finally {
                if (warmFile != null) {
                    try {
                        Files.deleteIfExists(warmFile);
                    } catch (Exception ignored) {
                    }
                }
                warmComplete.set(true);
            }
        }, Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "asr-warm");
            t.setDaemon(true);
            return t;
        }));
    }

    public boolean isWarmComplete() {
        return warmComplete.get();
    }

    private void waitForRagIndex() {
        long deadline = System.currentTimeMillis() + Math.max(30_000L, aiVoiceProperties.getOutboundReadyMaxWaitMs());
        while (!dialogRagRetrievalService.isIndexReady() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
