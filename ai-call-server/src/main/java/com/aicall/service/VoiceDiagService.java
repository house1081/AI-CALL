package com.aicall.service;

import com.aicall.config.AiVoiceProperties;
import com.aicall.util.FsHostOs;
import com.aicall.util.WavDurationUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 不依赖 SIP/外呼通道的语音链路离线诊断（SAPI → wav → 共享目录 → FS file_exists）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VoiceDiagService {

    private final LocalPromptWavService localPromptWavService;
    private final AiVoiceProperties aiVoiceProperties;
    private final FreeSwitchEslService eslService;

    public Map<String, Object> run(String text) {
        Map<String, Object> m = new LinkedHashMap<>();
        String sample = StringUtils.hasText(text) ? text.trim() : "您好，这是离线语音诊断测试。";
        m.put("text", sample);
        m.put("eslReachable", eslService.reachable());

        try {
            String id = "diag-" + System.currentTimeMillis();
            Path wav = localPromptWavService.synthesizeToFile(id, sample);
            long bytes = Files.size(wav);
            double sec = WavDurationUtil.durationSeconds(wav);
            m.put("sapiOk", bytes > 44 && sec >= 0.3);
            m.put("localWav", wav.toAbsolutePath().toString());
            m.put("bytes", bytes);
            m.put("durationSec", String.format("%.2f", sec));
            String base = aiVoiceProperties.getPlaybackBaseUrl();
            if (StringUtils.hasText(base)) {
                String b = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
                m.put("httpPlayUrl", b + "/uploads/tts/" + wav.getFileName());
            }
            m.put("listenHint", "用 Windows 媒体播放器双击 localWav，应能听到中文或明显蜂鸣");

            copyToSharedIfConfigured(m, wav, id);
        } catch (Exception e) {
            log.warn("语音离线诊断失败: {}", e.getMessage());
            m.put("sapiOk", false);
            m.put("error", e.getMessage());
        }
        return m;
    }

    private void copyToSharedIfConfigured(Map<String, Object> m, Path wav, String id) {
        String shared = aiVoiceProperties.getFsSharedWavDir();
        if (!StringUtils.hasText(shared) || !FsHostOs.isWindows(aiVoiceProperties.getFsHostOs())) {
            m.put("sharedDirSkipped", "未配置 Windows 共享目录");
            return;
        }
        try {
            String safeId = id.replace("-", "");
            String fileName = "aicall_" + safeId + ".wav";
            Path target = Path.of(shared.replace('/', '\\'), fileName);
            Files.createDirectories(target.getParent());
            Files.copy(wav, target, StandardCopyOption.REPLACE_EXISTING);
            String fsPath = FsHostOs.joinRemotePath(shared, fileName);
            m.put("sharedPath", fsPath);
            m.put("sharedBytes", Files.size(target));
            if (eslService.reachable()) {
                FreeSwitchEslService.EslResponse r = eslService.api("file_exists " + fsPath);
                String body = r.getBody() != null ? r.getBody() : r.getReplyText();
                m.put("fsFileExists", body != null && body.contains("true"));
                m.put("fsFileExistsReply", body);
            } else {
                m.put("fsFileExists", false);
                m.put("fsFileExistsReply", "ESL 不可达");
            }
            m.put("fsPlaybackHint",
                    "FS 控制台可试: file_exists " + fsPath
                            + " ；有 loopback 时: originate loopback/diag/default &playback(" + fsPath + ")");
        } catch (Exception e) {
            m.put("sharedCopyError", e.getMessage());
        }
    }
}
