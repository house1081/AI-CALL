package com.aicall.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * 管理端上传录音 → 外呼 8kHz/mono/16bit wav。
 * wav 直接重采样；mp4/mp3/m4a 需系统 PATH 中有 ffmpeg。
 */
public final class UploadedAudioConverter {

    private UploadedAudioConverter() {
    }

    public static boolean isSupportedUploadName(String filename) {
        if (filename == null || filename.isBlank()) {
            return false;
        }
        String lower = filename.toLowerCase(Locale.ROOT);
        return lower.endsWith(".wav") || lower.endsWith(".mp3")
                || lower.endsWith(".m4a") || lower.endsWith(".mp4");
    }

    public static void convertToTelephony8k(Path source, Path targetWav) throws IOException {
        if (source == null || targetWav == null || !Files.exists(source)) {
            throw new IOException("源文件不存在");
        }
        String lower = source.getFileName().toString().toLowerCase(Locale.ROOT);
        Files.createDirectories(targetWav.getParent());
        if (lower.endsWith(".wav")) {
            Files.copy(source, targetWav, StandardCopyOption.REPLACE_EXISTING);
            TelephonyWavUtil.convertFileToTelephony8k(targetWav);
            return;
        }
        if (lower.endsWith(".mp3") || lower.endsWith(".m4a") || lower.endsWith(".mp4")) {
            convertWithFfmpeg(source, targetWav);
            TelephonyWavUtil.convertFileToTelephony8k(targetWav);
            return;
        }
        throw new IOException("不支持的音频格式");
    }

    private static void convertWithFfmpeg(Path input, Path outputWav) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(
                "ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
                "-i", input.toAbsolutePath().toString(),
                "-ar", String.valueOf(TelephonyWavUtil.TELEPHONY_RATE),
                "-ac", "1",
                "-sample_fmt", "s16",
                outputWav.toAbsolutePath().toString());
        pb.redirectErrorStream(true);
        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            throw new IOException("mp4/mp3/m4a 需服务器安装 ffmpeg 并加入 PATH；也可先转为 wav 再上传", e);
        }
        try {
            boolean finished = process.waitFor(120, TimeUnit.SECONDS);
            String output = new String(process.getInputStream().readAllBytes());
            if (!finished) {
                process.destroyForcibly();
                throw new IOException("ffmpeg 转换超时");
            }
            if (process.exitValue() != 0) {
                throw new IOException("ffmpeg 转换失败: " + truncate(output, 240));
            }
            if (!Files.exists(outputWav) || Files.size(outputWav) <= 44) {
                throw new IOException("ffmpeg 未生成有效 wav");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IOException("ffmpeg 转换被中断");
        }
    }

    private static String truncate(String s, int max) {
        if (s == null || s.length() <= max) {
            return s != null ? s.trim() : "";
        }
        return s.substring(0, max).trim() + "…";
    }
}
