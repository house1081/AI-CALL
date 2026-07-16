package com.aicall.util;

import com.aicall.config.AiVoiceProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 外呼 ASR 输入：固定 8kHz / 单声道 / 16bit PCM wav（电话线路标准，避免识别率下降）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TelephonyAsrAudioUtil {

    private final AiVoiceProperties aiVoiceProperties;

    public Path prepareForAsr(Path wavFile) throws IOException {
        if (wavFile == null || !Files.exists(wavFile)) {
            throw new IOException("录音文件不存在");
        }
        try {
            TelephonyWavUtil.convertFileToTelephony8k(wavFile);
        } catch (Exception e) {
            log.warn("录音转 8kHz 失败，尝试直接校验: {} — {}", wavFile.getFileName(), e.getMessage());
        }
        byte[] raw = Files.readAllBytes(wavFile);
        if (raw.length <= 44) {
            throw new IOException("wav 过短或录音未完成");
        }
        validateTelephonyPcmWav(raw);
        double gain = aiVoiceProperties.getAsrInputGain();
        if (gain > 1.001) {
            amplifyPcmInWav(wavFile, gain);
        }
        try {
            byte[] afterGain = Files.readAllBytes(wavFile);
            byte[] boosted = TelephonyWavUtil.normalizeWavPeak(afterGain, 0.92);
            if (boosted != afterGain) {
                Files.write(wavFile, boosted);
            }
        } catch (Exception e) {
            log.debug("ASR 峰值归一化跳过: {}", e.getMessage());
        }
        log.debug("ASR 音频已规范为 8kHz/mono/16bit PCM wav: {}", wavFile.getFileName());
        return wavFile;
    }

    private static void amplifyPcmInWav(Path wavFile, double gain) throws IOException {
        byte[] raw = Files.readAllBytes(wavFile);
        int dataOffset = findDataOffset(raw);
        if (dataOffset < 0 || dataOffset + 2 >= raw.length) {
            return;
        }
        ByteBuffer buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = dataOffset; i + 1 < raw.length; i += 2) {
            short sample = buf.getShort(i);
            int amplified = (int) Math.round(sample * gain);
            if (amplified > Short.MAX_VALUE) {
                amplified = Short.MAX_VALUE;
            } else if (amplified < Short.MIN_VALUE) {
                amplified = Short.MIN_VALUE;
            }
            buf.putShort(i, (short) amplified);
        }
        Files.write(wavFile, raw);
    }

    private static int findDataOffset(byte[] raw) {
        ByteBuffer buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        if (raw.length < 44 || !readTag(buf, 4).equals("RIFF")) {
            return -1;
        }
        buf.getInt();
        if (!readTag(buf, 4).equals("WAVE")) {
            return -1;
        }
        while (buf.remaining() >= 8) {
            String chunk = readTag(buf, 4);
            int size = buf.getInt();
            if (size < 0 || size > buf.remaining()) {
                break;
            }
            if ("data".equals(chunk)) {
                return buf.position();
            }
            buf.position(buf.position() + size);
        }
        return -1;
    }

    public static void validateTelephonyPcmWav(byte[] wav) throws IOException {
        if (wav.length < 44) {
            throw new IOException("wav 过短");
        }
        ByteBuffer buf = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);
        if (!readTag(buf, 4).equals("RIFF")) {
            throw new IOException("非标准 wav：缺少 RIFF");
        }
        buf.getInt();
        if (!readTag(buf, 4).equals("WAVE")) {
            throw new IOException("非标准 wav：缺少 WAVE");
        }
        int channels = -1;
        int rate = -1;
        int bits = -1;
        int format = -1;
        boolean hasData = false;
        while (buf.remaining() >= 8) {
            String chunk = readTag(buf, 4);
            int size = buf.getInt();
            if (size < 0 || size > buf.remaining()) {
                break;
            }
            if ("fmt ".equals(chunk) && size >= 16) {
                format = buf.getShort() & 0xffff;
                channels = buf.getShort();
                rate = buf.getInt();
                buf.getInt();
                buf.getShort();
                bits = buf.getShort();
                int skip = size - 16;
                if (skip > 0) {
                    buf.position(buf.position() + skip);
                }
            } else if ("data".equals(chunk)) {
                hasData = true;
                buf.position(buf.position() + size);
            } else {
                buf.position(buf.position() + size);
            }
        }
        if (format != 1) {
            throw new IOException("ASR 需要 PCM(1) 编码，当前 format=" + format + "（请勿使用 MP3/压缩格式）");
        }
        if (rate != TelephonyWavUtil.TELEPHONY_RATE) {
            throw new IOException("ASR 需要 8000Hz，当前 sampleRate=" + rate);
        }
        if (channels != 1) {
            throw new IOException("ASR 需要单声道，当前 channels=" + channels);
        }
        if (bits != 16) {
            throw new IOException("ASR 需要 16bit，当前 bits=" + bits);
        }
        if (!hasData) {
            throw new IOException("wav 无 data 块");
        }
    }

    public static short[] readMonoPcm16(Path wavFile) throws IOException {
        byte[] telephony = TelephonyWavUtil.toTelephony8kMono(Files.readAllBytes(wavFile));
        ByteBuffer buf = ByteBuffer.wrap(telephony).order(ByteOrder.LITTLE_ENDIAN);
        buf.position(44);
        int samples = (telephony.length - 44) / 2;
        short[] out = new short[samples];
        for (int i = 0; i < samples; i++) {
            out[i] = buf.getShort();
        }
        return out;
    }

    /** 录音进行中轮询：仅读取已写入的 PCM，不强制完整 wav */
    public static short[] readAvailablePcm16ForVad(Path wavFile) throws IOException {
        return readAvailablePcm16Samples(Files.readAllBytes(wavFile), false);
    }

    private static short[] readAvailablePcm16Samples(byte[] raw, boolean strict) throws IOException {
        if (raw.length < 44) {
            if (strict) {
                throw new IOException("wav 过短");
            }
            return new short[0];
        }
        ByteBuffer buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        if (!readTag(buf, 4).equals("RIFF")) {
            if (strict) {
                throw new IOException("非 RIFF");
            }
            return new short[0];
        }
        buf.getInt();
        if (!readTag(buf, 4).equals("WAVE")) {
            if (strict) {
                throw new IOException("非 WAVE");
            }
            return new short[0];
        }
        int channels = 1;
        int rate = TelephonyWavUtil.TELEPHONY_RATE;
        int bits = 16;
        int format = 1;
        byte[] pcm = null;
        while (buf.remaining() >= 8) {
            String chunk = readTag(buf, 4);
            int size = buf.getInt();
            if (size < 0) {
                break;
            }
            int chunkDataPos = buf.position();
            int available = raw.length - chunkDataPos;
            int readSize = Math.min(size, available);
            if ("fmt ".equals(chunk) && readSize >= 16) {
                format = buf.getShort() & 0xffff;
                channels = buf.getShort();
                rate = buf.getInt();
                buf.position(chunkDataPos + readSize);
            } else if ("data".equals(chunk)) {
                if (readSize < 2) {
                    break;
                }
                pcm = new byte[readSize];
                buf.get(pcm);
                break;
            } else {
                buf.position(chunkDataPos + readSize);
            }
        }
        if (pcm == null || pcm.length < 2) {
            return new short[0];
        }
        if (strict) {
            if (format != 1) {
                throw new IOException("需要 PCM format=1");
            }
            if (rate != TelephonyWavUtil.TELEPHONY_RATE) {
                throw new IOException("需要 8000Hz");
            }
            if (channels != 1) {
                throw new IOException("需要单声道");
            }
            if (bits != 16) {
                throw new IOException("需要 16bit");
            }
        }
        int frameBytes = 2 * Math.max(1, channels);
        int frames = pcm.length / frameBytes;
        short[] mono = new short[frames];
        ByteBuffer pcmBuf = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < frames; i++) {
            mono[i] = pcmBuf.getShort();
            if (channels > 1 && pcmBuf.remaining() >= 2) {
                pcmBuf.getShort();
            }
        }
        return mono;
    }

    private static String readTag(ByteBuffer buf, int len) {
        byte[] b = new byte[len];
        buf.get(b);
        return new String(b);
    }
}
