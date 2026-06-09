package com.aicall.service;

import com.aicall.config.AiVoiceProperties;
import com.aicall.util.FsHostOs;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 接通后把预合成开场白拷贝到通话 wav 路径（无现场 TTS 时回退异步合成）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OpeningVoicePrewarmService {

    private final AiVoiceProperties aiVoiceProperties;
    private final TtsPhraseCacheService ttsPhraseCacheService;
    private final OpeningVoiceCacheService openingVoiceCacheService;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "opening-prewarm");
        t.setDaemon(true);
        return t;
    });

    private final Map<String, CompletableFuture<Path>> pending = new ConcurrentHashMap<>();

    public void schedule(String fsUuid, String openingText) {
        if (!StringUtils.hasText(fsUuid)) {
            return;
        }
        if (aiVoiceProperties.isOpeningVoicePrecacheEnabled()) {
            Path cached = openingVoiceCacheService.copyToCallPlayback(fsUuid);
            if (cached != null) {
                pending.put(fsUuid, CompletableFuture.completedFuture(cached));
                return;
            }
            // 预缓存未就绪时不并行抢 CosyVoice，由对话线程单次合成，避免 428
            log.debug("[开场预热] 无预缓存，跳过并行 TTS uuid={}", fsUuid);
            return;
        }
        if (!StringUtils.hasText(openingText) || !ttsPhraseCacheService.isAvailable()) {
            return;
        }
        Path target = sharedWavPath(fsUuid);
        pending.put(fsUuid, CompletableFuture.supplyAsync(() -> {
            try {
                Files.createDirectories(target.getParent());
                Path out = ttsPhraseCacheService.synthesizeToFile(target, openingText);
                if (out != null && Files.exists(out) && Files.size(out) > 44) {
                    log.info("[开场预热] 已写入共享 wav uuid={} bytes={}", fsUuid, Files.size(out));
                    return out;
                }
            } catch (Exception e) {
                log.warn("[开场预热] 合成失败 uuid={}: {}", fsUuid, e.getMessage());
            }
            return null;
        }, executor));
    }

    /**
     * @param waitMs 最多等待预热完成的时间；超时则返回 null，由调用方走常规 TTS
     */
    public Path awaitSharedWav(String fsUuid, long waitMs) {
        if (aiVoiceProperties.isOpeningVoicePrecacheEnabled()) {
            Path cached = openingVoiceCacheService.copyToCallPlayback(fsUuid);
            if (cached != null) {
                pending.remove(fsUuid);
                return cached;
            }
        }
        CompletableFuture<Path> future = pending.remove(fsUuid);
        if (future == null) {
            return existingSharedWav(fsUuid);
        }
        try {
            Path p = future.get(Math.max(0, waitMs), TimeUnit.MILLISECONDS);
            if (p != null && Files.exists(p) && Files.size(p) > 44) {
                return p;
            }
        } catch (java.util.concurrent.TimeoutException e) {
            if (future.isDone()) {
                try {
                    Path p = future.getNow(null);
                    if (p != null && Files.exists(p) && Files.size(p) > 44) {
                        return p;
                    }
                } catch (Exception ignored) {
                }
            }
            log.debug("[开场预热] {}ms 内未完成，走常规合成 uuid={}", waitMs, fsUuid);
        } catch (Exception e) {
            log.warn("[开场预热] 等待异常 uuid={}: {}", fsUuid, e.getMessage());
        }
        return existingSharedWav(fsUuid);
    }

    public void cancel(String fsUuid) {
        CompletableFuture<Path> f = pending.remove(fsUuid);
        if (f != null) {
            f.cancel(true);
        }
    }

    private Path existingSharedWav(String fsUuid) {
        Path p = sharedWavPath(fsUuid);
        try {
            if (Files.exists(p) && Files.size(p) > 44) {
                return p;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private Path sharedWavPath(String fsUuid) {
        String dir = aiVoiceProperties.getFsSharedWavDir();
        if (!StringUtils.hasText(dir)) {
            dir = aiVoiceProperties.getFsTempWavDir();
        }
        String fileName = "aicall_" + fsUuid.replace("-", "") + ".wav";
        return Path.of(dir.replace('/', '\\'), fileName);
    }
}
