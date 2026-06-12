package com.aicall.util;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

/** 电话场景测试/预热用 8kHz mono 16bit WAV */
public final class TelephonyWavSamples {

    private TelephonyWavSamples() {
    }

    public static Path writeSilenceWav(Path out, int sampleRateHz, int durationMs) throws IOException {
        int samples = Math.max(1, sampleRateHz * durationMs / 1000);
        byte[] pcm = new byte[samples * 2];
        byte[] wav = buildWav(pcm, sampleRateHz, 1, 16);
        Files.createDirectories(out.getParent() != null ? out.getParent() : Path.of("."));
        Files.write(out, wav);
        return out;
    }

    private static byte[] buildWav(byte[] pcm, int sampleRate, int channels, int bitsPerSample) {
        int byteRate = sampleRate * channels * bitsPerSample / 8;
        int blockAlign = channels * bitsPerSample / 8;
        byte[] header = new byte[44];
        ByteBuffer buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        buf.put("RIFF".getBytes());
        buf.putInt(36 + pcm.length);
        buf.put("WAVE".getBytes());
        buf.put("fmt ".getBytes());
        buf.putInt(16);
        buf.putShort((short) 1);
        buf.putShort((short) channels);
        buf.putInt(sampleRate);
        buf.putInt(byteRate);
        buf.putShort((short) blockAlign);
        buf.putShort((short) bitsPerSample);
        buf.put("data".getBytes());
        buf.putInt(pcm.length);
        byte[] wav = new byte[header.length + pcm.length];
        System.arraycopy(header, 0, wav, 0, header.length);
        System.arraycopy(pcm, 0, wav, header.length, pcm.length);
        return wav;
    }
}
