package com.aicall.util;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

/** 读取 wav 单声道 PCM（保留原始采样率，供浏览器 ASR/VAD 使用） */
public final class WavPcmUtil {

    public record MonoPcm(int sampleRate, short[] samples) {
        public int durationMs() {
            if (samples == null || samples.length == 0 || sampleRate <= 0) {
                return 0;
            }
            return samples.length * 1000 / sampleRate;
        }
    }

    private WavPcmUtil() {
    }

    public static MonoPcm readMono(Path wavFile) throws IOException {
        byte[] raw = Files.readAllBytes(wavFile);
        if (raw.length < 44) {
            throw new IOException("wav 过短");
        }
        ByteBuffer buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        if (!readTag(buf, 4).equals("RIFF")) {
            throw new IOException("非标准 wav");
        }
        buf.getInt();
        if (!readTag(buf, 4).equals("WAVE")) {
            throw new IOException("非 WAVE");
        }
        int channels = 1;
        int rate = 16000;
        int bits = 16;
        int format = 1;
        byte[] pcm = null;
        while (buf.remaining() >= 8) {
            String chunk = readTag(buf, 4);
            int size = buf.getInt();
            if (size < 0 || size > buf.remaining()) {
                break;
            }
            int dataPos = buf.position();
            if ("fmt ".equals(chunk) && size >= 16) {
                format = buf.getShort() & 0xffff;
                channels = buf.getShort();
                rate = buf.getInt();
                buf.position(dataPos + size);
            } else if ("data".equals(chunk)) {
                pcm = new byte[size];
                buf.get(pcm);
                break;
            } else {
                buf.position(dataPos + size);
            }
        }
        if (format != 1 || pcm == null || pcm.length < 2) {
            throw new IOException("wav 无有效 PCM data");
        }
        int frameBytes = Math.max(2, (bits / 8) * Math.max(1, channels));
        int frames = pcm.length / frameBytes;
        short[] mono = new short[frames];
        ByteBuffer pcmBuf = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < frames; i++) {
            mono[i] = pcmBuf.getShort();
            for (int c = 1; c < channels && pcmBuf.remaining() >= 2; c++) {
                pcmBuf.getShort();
            }
        }
        return new MonoPcm(rate, mono);
    }

    public static int peakRms(short[] samples) {
        if (samples == null || samples.length == 0) {
            return 0;
        }
        long sum = 0;
        for (short s : samples) {
            sum += (long) s * s;
        }
        return (int) Math.sqrt((double) sum / samples.length);
    }

    /** 原地增益 PCM 并写回 wav（浏览器麦克风音量过小时使用） */
    public static void amplifyFile(Path wavFile, double gain) throws IOException {
        if (gain <= 1.001) {
            return;
        }
        byte[] raw = Files.readAllBytes(wavFile);
        byte[] boosted = TelephonyWavUtil.normalizeWavPeak(raw, 0.98);
        ByteBuffer buf = ByteBuffer.wrap(boosted).order(ByteOrder.LITTLE_ENDIAN);
        if (boosted.length < 44) {
            return;
        }
        int dataOffset = findDataOffset(boosted);
        if (dataOffset < 0) {
            Files.write(wavFile, boosted);
            return;
        }
        for (int i = dataOffset; i + 1 < boosted.length; i += 2) {
            short sample = buf.getShort(i);
            int amplified = (int) Math.round(sample * gain);
            if (amplified > Short.MAX_VALUE) {
                amplified = Short.MAX_VALUE;
            } else if (amplified < Short.MIN_VALUE) {
                amplified = Short.MIN_VALUE;
            }
            buf.putShort(i, (short) amplified);
        }
        boosted = TelephonyWavUtil.normalizeWavPeak(boosted, 0.95);
        Files.write(wavFile, boosted);
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

    private static String readTag(ByteBuffer buf, int len) {
        byte[] b = new byte[len];
        buf.get(b);
        return new String(b);
    }
}
