package com.aicall.service;

import com.aicall.config.AiVoiceProperties;
import com.aicall.util.FsHostOs;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 整通通话录音：接通后 {@code uuid_record} 写入 {@code fs-record-dir}，挂断后复制到 {@code uploads/record} 供后台播放。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CallSessionRecordService {

    private final AiVoiceProperties aiVoiceProperties;
    private final FreeSwitchEslService eslService;

    private final Map<String, ActiveSession> active = new ConcurrentHashMap<>();

    public void startSessionRecord(String uuid, Integer callRecordId) {
        if (!aiVoiceProperties.isCallRecordEnabled() || !StringUtils.hasText(uuid)) {
            return;
        }
        try {
            String dir = resolveRecordDir();
            String fileName = "call_" + callRecordId + "_"
                    + uuid.replace("-", "") + ".wav";
            String fsPath = FsHostOs.joinRemotePath(dir, fileName);
            Path localPath = Path.of(dir.replace('/', '\\'), fileName);
            Files.createDirectories(localPath.getParent());
            eslService.bgapi("system " + FsHostOs.mkdirCommand(aiVoiceProperties.getFsHostOs(), dir));

            prepareRecordChannel(uuid);
            int limitSec = Math.max(60, aiVoiceProperties.getDialogMaxCallSec() + 30);
            // bgapi 避免同步 api 阻塞对话线程（曾出现「全程录音已开始」后再无 TTS 日志）
            eslService.bgapi("uuid_record " + uuid + " start " + fsPath + " " + limitSec);
            active.put(uuid, new ActiveSession(fsPath, localPath, 0L));
            log.info("[全程录音] 已开始 uuid={} callRecordId={} path={} limitSec={}",
                    uuid, callRecordId, fsPath, limitSec);
        } catch (Exception e) {
            log.warn("[全程录音] 启动异常 uuid={}: {}", uuid, e.getMessage());
        }
    }

    public boolean isSessionRecording(String uuid) {
        return active.containsKey(uuid);
    }

    public Path getSessionLocalPath(String uuid) {
        ActiveSession s = active.get(uuid);
        return s != null ? s.localPath() : null;
    }

    public long getAsrReadOffset(String uuid) {
        ActiveSession s = active.get(uuid);
        return s != null ? s.asrOffsetBytes() : 0L;
    }

    public void setAsrReadOffset(String uuid, long offset) {
        ActiveSession s = active.get(uuid);
        if (s != null) {
            s.setAsrOffsetBytes(offset);
        }
    }

    /** TTS 开始播前对齐基线，插嘴检测只分析此后新增 PCM */
    public void markBargeInBaseline(String uuid) {
        syncAsrBaselineAfterPlayback(uuid);
    }

    /** TTS 播完后对齐基线，避免下一轮 ASR 吃到 AI 播报回声 */
    public void syncAsrBaselineAfterPlayback(String uuid) {
        if (!aiVoiceProperties.isSyncAsrBaselineAfterPlayback()) {
            return;
        }
        ActiveSession s = active.get(uuid);
        if (s == null) {
            return;
        }
        try {
            if (Files.exists(s.localPath())) {
                long size = Files.size(s.localPath());
                s.setAsrOffsetBytes(size);
                log.debug("[全程录音] ASR 基线已对齐 uuid={} offset={}", uuid, size);
            }
        } catch (Exception e) {
            log.trace("[全程录音] 对齐 ASR 基线失败 uuid={}: {}", uuid, e.getMessage());
        }
    }

    /**
     * 停止录音并发布 HTTP 路径；无有效文件时返回空串。
     */
    public String stopAndPublish(String uuid, Integer callRecordId) {
        if (!StringUtils.hasText(uuid)) {
            return "";
        }
        ActiveSession session = active.remove(uuid);
        if (session == null) {
            return "";
        }
        try {
            eslService.api("uuid_record " + uuid + " stop " + session.fsPath());
            eslService.api("uuid_record " + uuid + " stop all");
            waitForFile(session.localPath(), session.fsPath(), 4000);
            if (!Files.exists(session.localPath()) || Files.size(session.localPath()) <= 44) {
                log.warn("[全程录音] 文件无效 uuid={} path={}", uuid, session.localPath());
                return "";
            }
            Path publishDir = Path.of(aiVoiceProperties.getCallRecordPublishDir()
                    .replace('/', '\\'));
            Files.createDirectories(publishDir);
            Path published = publishDir.resolve(session.localPath().getFileName());
            Files.copy(session.localPath(), published, StandardCopyOption.REPLACE_EXISTING);
            String url = "/uploads/record/" + published.getFileName();
            log.info("[全程录音] 已落盘 uuid={} callRecordId={} bytes={} url={}",
                    uuid, callRecordId, Files.size(published), url);
            return url;
        } catch (Exception e) {
            log.warn("[全程录音] 停止/发布失败 uuid={}: {}", uuid, e.getMessage());
            return "";
        }
    }

    private void prepareRecordChannel(String uuid) {
        eslService.api("uuid_setvar " + uuid + " record_session true");
        eslService.api("uuid_setvar " + uuid + " RECORD_STEREO false");
        eslService.api("uuid_setvar " + uuid + " record_sample_rate 8000");
        eslService.api("uuid_setvar " + uuid + " enable_file_write_buffering false");
    }

    private String resolveRecordDir() {
        String dir = aiVoiceProperties.getFsRecordDir();
        if (!StringUtils.hasText(dir)) {
            dir = aiVoiceProperties.getFsSharedWavDir();
        }
        if (!StringUtils.hasText(dir)) {
            dir = aiVoiceProperties.getFsTempWavDir();
        }
        return dir;
    }

    private void waitForFile(Path localPath, String fsPath, int maxWaitMs) throws InterruptedException {
        int steps = Math.max(1, maxWaitMs / 100);
        for (int i = 0; i < steps; i++) {
            if (localFileReady(localPath)) {
                return;
            }
            if (fsFileExists(fsPath)) {
                Thread.sleep(100L);
                if (localFileReady(localPath)) {
                    return;
                }
            }
            Thread.sleep(100L);
        }
    }

    private static boolean localFileReady(Path localPath) {
        try {
            return Files.exists(localPath) && Files.size(localPath) > 44;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean fsFileExists(String path) {
        try {
            FreeSwitchEslService.EslResponse r = eslService.api("file_exists " + path);
            String body = r.getBody() != null ? r.getBody() : "";
            return body.contains("true");
        } catch (Exception e) {
            return false;
        }
    }

    private static final class ActiveSession {
        private final String fsPath;
        private final Path localPath;
        private volatile long asrOffsetBytes;

        private ActiveSession(String fsPath, Path localPath, long asrOffsetBytes) {
            this.fsPath = fsPath;
            this.localPath = localPath;
            this.asrOffsetBytes = asrOffsetBytes;
        }

        String fsPath() {
            return fsPath;
        }

        Path localPath() {
            return localPath;
        }

        long asrOffsetBytes() {
            return asrOffsetBytes;
        }

        void setAsrOffsetBytes(long asrOffsetBytes) {
            this.asrOffsetBytes = asrOffsetBytes;
        }
    }
}
