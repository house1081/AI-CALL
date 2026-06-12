package com.aicall.service;

import com.aicall.common.TtsSynthesisException;
import com.aicall.config.AiVoiceProperties;
import com.aicall.util.TelephonyWavUtil;
import com.aicall.util.WavDurationUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

/**
 * 本地 8kHz wav：优先 DashScope CosyVoice 仿真人声，其次 Windows SAPI。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LocalPromptWavService {

    private static final int SAMPLE_RATE = TelephonyWavUtil.TELEPHONY_RATE;

    private final AiVoiceProperties aiVoiceProperties;
    private final TtsPhraseCacheService ttsPhraseCacheService;

    /** 固定话术预录：CosyVoice 不可用或 418 时仅用 Windows SAPI（不再次请求云端） */
    public Path synthesizeLocalFallbackToFile(Path out, String text) throws IOException {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        Files.createDirectories(out.getParent());
        if (tryWindowsSapi(text, out)) {
            normalizeTelephonyWav(out);
            return out;
        }
        if (!aiVoiceProperties.isEnableToneFallback()) {
            return null;
        }
        writeBeepWav(out, 1500);
        return out;
    }

    public Path synthesizeToFile(String fsUuid, String text) throws IOException {
        Path dir = Paths.get("./uploads/tts");
        Files.createDirectories(dir);
        String name = (StringUtils.hasText(fsUuid) ? fsUuid : "prompt")
                + "_" + System.currentTimeMillis() + ".wav";
        Path out = dir.resolve(name);

        if (useCosyVoice()) {
            try {
                if (ttsPhraseCacheService.synthesizeToFile(out, text) != null) {
                    return out;
                }
            } catch (TtsSynthesisException e) {
                if (e.isRateLimited()) {
                    log.warn("[TTS] CosyVoice 限流，回退本地 SAPI/蜂鸣 uuid={}: {}",
                            fsUuid, e.getMessage());
                } else {
                    throw e;
                }
            }
        }
        if (StringUtils.hasText(text) && tryWindowsSapi(text, out)) {
            normalizeTelephonyWav(out);
            return out;
        }
        if (!aiVoiceProperties.isEnableToneFallback()) {
            throw new IOException("TTS 不可用且已关闭蜂鸣回退");
        }
        Path startup = Paths.get("./uploads/tts/startup-beep.wav");
        if (Files.exists(startup) && Files.size(startup) > 44) {
            Files.copy(startup, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return out;
        }
        writeBeepWav(out, 1500);
        return out;
    }

    private boolean useCosyVoice() {
        String mode = aiVoiceProperties.getTtsMode();
        return ("cosyvoice".equalsIgnoreCase(mode) || "auto".equalsIgnoreCase(mode))
                && ttsPhraseCacheService.isAvailable();
    }

    private boolean tryWindowsSapi(String text, Path out) {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (!os.contains("win")) {
            return false;
        }
        try {
            Path textFile = out.getParent().resolve(out.getFileName().toString() + ".txt");
            Files.writeString(textFile, text.length() > 300 ? text.substring(0, 300) : text,
                    java.nio.charset.StandardCharsets.UTF_8);
            String outPath = out.toAbsolutePath().toString().replace("'", "''");
            String txtPath = textFile.toAbsolutePath().toString().replace("'", "''");
            String ps = String.format(
                    "Add-Type -AssemblyName System.Speech; "
                            + "$s = New-Object System.Speech.Synthesis.SpeechSynthesizer; "
                            + "$zh = $s.GetInstalledVoices() | Where-Object { $_.VoiceInfo.Culture.Name -like 'zh*' } | Select-Object -First 1; "
                            + "if ($zh) { $s.SelectVoice($zh.VoiceInfo.Name) }; "
                            + "$s.Rate = %d; "
                            + "$s.Volume = %d; "
                            + "$s.SetOutputToWaveFile('%s'); "
                            + "$t = Get-Content -LiteralPath '%s' -Encoding UTF8 -Raw; "
                            + "$s.Speak($t); $s.Dispose(); "
                            + "Remove-Item -LiteralPath '%s' -Force -ErrorAction SilentlyContinue",
                    sapiRateFromMultiplier(aiVoiceProperties.getTtsSpeechRate()),
                    Math.min(100, Math.max(50, aiVoiceProperties.getTtsVolume())),
                    outPath, txtPath, txtPath);
            Process p = new ProcessBuilder(
                    "powershell", "-NoProfile", "-NonInteractive", "-Command", ps)
                    .redirectErrorStream(true)
                    .start();
            boolean ok = p.waitFor(25, TimeUnit.SECONDS);
            String psOut = new String(p.getInputStream().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8).trim();
            if (!ok) {
                p.destroyForcibly();
                log.warn("Windows SAPI 超时: {}", truncate(psOut, 200));
                return false;
            }
            if (p.exitValue() != 0) {
                log.warn("Windows SAPI 退出码 {}: {}", p.exitValue(), truncate(psOut, 200));
                return false;
            }
            if (!Files.exists(out) || Files.size(out) <= 44) {
                log.warn("Windows SAPI 未生成有效 wav");
                return false;
            }
            return true;
        } catch (Exception e) {
            log.warn("Windows SAPI 失败: {}", e.getMessage());
            return false;
        }
    }

    private void normalizeTelephonyWav(Path out) throws IOException {
        byte[] raw = Files.readAllBytes(out);
        byte[] telephony = TelephonyWavUtil.toTelephony8kMono(raw);
        telephony = TelephonyWavUtil.normalizeWavPeak(telephony, aiVoiceProperties.getPlaybackPeakRatio());
        Files.write(out, telephony);
        double sec = WavDurationUtil.durationSeconds(out);
        log.debug("已生成 8kHz 语音 {} ({} bytes, 约 {}s)", out.getFileName(), telephony.length,
                String.format("%.2f", sec));
    }

    public void writeBeepWav(Path out, int freqHz) throws IOException {
        int durationMs = 1500;
        int samples = SAMPLE_RATE * durationMs / 1000;
        byte[] pcm = new byte[samples * 2];
        for (int i = 0; i < samples; i++) {
            double t = (double) i / SAMPLE_RATE;
            short v = (short) (Math.sin(2 * Math.PI * freqHz * t) * 8000);
            pcm[i * 2] = (byte) (v & 0xff);
            pcm[i * 2 + 1] = (byte) ((v >> 8) & 0xff);
        }
        byte[] wav = TelephonyWavUtil.buildWav(pcm, SAMPLE_RATE, 1, 16);
        Files.createDirectories(out.getParent());
        Files.write(out, wav);
    }

    private static int sapiRateFromMultiplier(double rate) {
        if (rate <= 0.85) {
            return -2;
        }
        if (rate <= 0.95) {
            return -1;
        }
        if (rate >= 1.1) {
            return 1;
        }
        return 0;
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
