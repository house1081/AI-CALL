package com.aicall.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 外呼常用：8kHz、16bit、单声道 PCM wav。
 */
public final class TelephonyWavUtil {

    public static final int TELEPHONY_RATE = 8000;

    private TelephonyWavUtil() {
    }

    public static void convertFileToTelephony8k(Path wavFile) throws IOException {
        byte[] raw = Files.readAllBytes(wavFile);
        byte[] telephony = toTelephony8kMono(raw);
        telephony = normalizeWavPeak(telephony, 0.88);
        Files.write(wavFile, telephony);
    }

    /** 将 PCM 峰值提升到电话线路可听范围（SAPI/重采样后常偏小） */
    public static byte[] normalizeWavPeak(byte[] wavBytes, double peakRatio) throws IOException {
        if (wavBytes.length < 44) {
            return wavBytes;
        }
        ByteBuffer buf = ByteBuffer.wrap(wavBytes).order(ByteOrder.LITTLE_ENDIAN);
        if (!readFourCC(buf).equals("RIFF")) {
            return wavBytes;
        }
        buf.getInt();
        if (!readFourCC(buf).equals("WAVE")) {
            return wavBytes;
        }
        int channels = 1;
        int bitsPerSample = 16;
        byte[] pcm = null;
        while (buf.remaining() >= 8) {
            String chunk = readFourCC(buf);
            int size = buf.getInt();
            if (size < 0 || size > buf.remaining()) {
                break;
            }
            if ("fmt ".equals(chunk) && size >= 16) {
                buf.getShort();
                channels = buf.getShort();
                buf.getInt();
                buf.getInt();
                buf.getShort();
                bitsPerSample = buf.getShort();
                int skip = size - 16;
                if (skip > 0) {
                    buf.position(buf.position() + skip);
                }
            } else if ("data".equals(chunk)) {
                byte[] data = new byte[size];
                buf.get(data);
                pcm = data;
                break;
            } else {
                skipChunk(buf, size);
            }
        }
        if (pcm == null || bitsPerSample != 16) {
            return wavBytes;
        }
        short[] samples = pcm16ToSamples(pcm, bitsPerSample, channels);
        int peak = 0;
        for (short s : samples) {
            peak = Math.max(peak, Math.abs(s));
        }
        if (peak < 80) {
            return wavBytes;
        }
        double target = 32767.0 * Math.max(0.5, Math.min(1.0, peakRatio));
        double gain = target / peak;
        if (gain > 8.0) {
            gain = 8.0;
        }
        if (gain < 1.05) {
            return wavBytes;
        }
        for (int i = 0; i < samples.length; i++) {
            int v = (int) Math.round(samples[i] * gain);
            if (v > 32767) {
                v = 32767;
            } else if (v < -32768) {
                v = -32768;
            }
            samples[i] = (short) v;
        }
        return buildWav(samplesToPcm16(samples), TELEPHONY_RATE, 1, 16);
    }

    /**
     * 将任意音频字节转为 8kHz/mono/16bit PCM wav。支持：纯 PCM、标准 wav、SSE 多段 wav 拼接。
     */
    public static byte[] toTelephony8kMono(byte[] audioBytes) throws IOException {
        if (audioBytes == null || audioBytes.length < 2) {
            throw new IOException("音频过短");
        }
        if (!startsWith(audioBytes, "RIFF")) {
            return buildWavFromRawPcm(audioBytes, TELEPHONY_RATE, 1, 16);
        }
        ParsedWav parsed = parseWavPcm(audioBytes);
        if (parsed.pcm.length == 0) {
            throw new IOException("wav 无 data 块");
        }
        short[] samples = pcm16ToSamples(parsed.pcm, parsed.bitsPerSample, parsed.channels);
        short[] resampled = resample(samples, parsed.sampleRate, TELEPHONY_RATE);
        return buildWav(samplesToPcm16(resampled), TELEPHONY_RATE, 1, 16);
    }

    /** SSE/PCM 流：已是 8k 16bit 单声道时直接封装 wav */
    public static byte[] buildWavFromRawPcm(byte[] pcm, int sampleRate, int channels, int bits) {
        if (pcm == null || pcm.length < 2) {
            throw new IllegalArgumentException("PCM 为空");
        }
        if (sampleRate == TELEPHONY_RATE && channels == 1 && bits == 16) {
            return buildWav(pcm, TELEPHONY_RATE, 1, 16);
        }
        try {
            short[] samples = pcm16ToSamples(pcm, bits, channels);
            short[] resampled = resample(samples, sampleRate, TELEPHONY_RATE);
            return buildWav(samplesToPcm16(resampled), TELEPHONY_RATE, 1, 16);
        } catch (Exception e) {
            return buildWav(pcm, TELEPHONY_RATE, 1, 16);
        }
    }

    private static boolean startsWith(byte[] data, String tag) {
        if (data.length < tag.length()) {
            return false;
        }
        for (int i = 0; i < tag.length(); i++) {
            if (data[i] != tag.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private static ParsedWav parseWavPcm(byte[] wavBytes) throws IOException {
        ByteArrayOutputStream pcmAll = new ByteArrayOutputStream();
        int channels = 1;
        int sampleRate = TELEPHONY_RATE;
        int bitsPerSample = 16;
        int offset = 0;
        while (offset + 12 < wavBytes.length) {
            if (!startsWithAt(wavBytes, offset, "RIFF")) {
                offset++;
                continue;
            }
            ByteBuffer buf = ByteBuffer.wrap(wavBytes, offset, wavBytes.length - offset)
                    .order(ByteOrder.LITTLE_ENDIAN);
            if (!readFourCC(buf).equals("RIFF")) {
                offset++;
                continue;
            }
            buf.getInt();
            if (!readFourCC(buf).equals("WAVE")) {
                offset++;
                continue;
            }
            while (buf.remaining() >= 8) {
                String chunk = readFourCC(buf);
                int size = buf.getInt();
                if (size < 0 || size > buf.remaining()) {
                    break;
                }
                if ("fmt ".equals(chunk) && size >= 16) {
                    int audioFormat = buf.getShort() & 0xffff;
                    channels = buf.getShort();
                    sampleRate = buf.getInt();
                    buf.getInt();
                    buf.getShort();
                    bitsPerSample = buf.getShort();
                    int skip = size - 16;
                    if (skip > 0) {
                        buf.position(buf.position() + skip);
                    }
                    if (audioFormat != 1) {
                        throw new IOException("仅支持 PCM wav，format=" + audioFormat);
                    }
                } else if ("data".equals(chunk)) {
                    byte[] data = new byte[size];
                    buf.get(data);
                    pcmAll.write(data, 0, data.length);
                } else {
                    skipChunk(buf, size);
                }
            }
            offset += Math.max(12, buf.position());
        }
        return new ParsedWav(pcmAll.toByteArray(), sampleRate, channels, bitsPerSample);
    }

    private static boolean startsWithAt(byte[] data, int offset, String tag) {
        if (offset + tag.length() > data.length) {
            return false;
        }
        for (int i = 0; i < tag.length(); i++) {
            if (data[offset + i] != tag.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private record ParsedWav(byte[] pcm, int sampleRate, int channels, int bitsPerSample) {
    }

    /** 8kHz/mono/16bit wav 的 PCM 时长（毫秒）；解析失败返回 -1 */
    public static long pcmDurationMs(byte[] wavBytes) {
        if (wavBytes == null || wavBytes.length < 44) {
            return -1;
        }
        try {
            ParsedWav parsed = parseWavPcm(wavBytes);
            if (parsed.pcm.length < 2 || parsed.sampleRate <= 0) {
                return -1;
            }
            int bytesPerSample = Math.max(1, parsed.bitsPerSample / 8);
            int frameBytes = bytesPerSample * Math.max(1, parsed.channels);
            long frames = parsed.pcm.length / frameBytes;
            return frames * 1000L / parsed.sampleRate;
        } catch (Exception e) {
            return -1;
        }
    }

    public static long pcmDurationMs(Path wavFile) {
        try {
            return pcmDurationMs(Files.readAllBytes(wavFile));
        } catch (Exception e) {
            return -1;
        }
    }

    public static byte[] buildWav(byte[] pcm, int rate, int channels, int bits) {
        int byteRate = rate * channels * bits / 8;
        ByteBuffer buf = ByteBuffer.allocate(44 + pcm.length).order(ByteOrder.LITTLE_ENDIAN);
        buf.put("RIFF".getBytes());
        buf.putInt(36 + pcm.length);
        buf.put("WAVE".getBytes());
        buf.put("fmt ".getBytes());
        buf.putInt(16);
        buf.putShort((short) 1);
        buf.putShort((short) channels);
        buf.putInt(rate);
        buf.putInt(byteRate);
        buf.putShort((short) (channels * bits / 8));
        buf.putShort((short) bits);
        buf.put("data".getBytes());
        buf.putInt(pcm.length);
        buf.put(pcm);
        return buf.array();
    }

    private static void skipChunk(ByteBuffer buf, int size) {
        buf.position(buf.position() + size);
        if ((size & 1) != 0 && buf.hasRemaining()) {
            buf.get();
        }
    }

    private static String readFourCC(ByteBuffer buf) {
        byte[] b = new byte[4];
        buf.get(b);
        return new String(b);
    }

    private static short[] pcm16ToSamples(byte[] pcm, int bits, int channels) {
        if (bits != 16) {
            throw new IllegalArgumentException("仅支持 16bit");
        }
        int frameCount = pcm.length / 2 / channels;
        short[] mono = new short[frameCount];
        ByteBuffer bb = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < frameCount; i++) {
            int sum = 0;
            for (int c = 0; c < channels; c++) {
                sum += bb.getShort();
            }
            mono[i] = (short) (sum / channels);
        }
        return mono;
    }

    public static byte[] samplesToPcm16(short[] samples) {
        ByteBuffer bb = ByteBuffer.allocate(samples.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (short s : samples) {
            bb.putShort(s);
        }
        return bb.array();
    }

    public static byte[] buildWavFromPcm16(short[] samples, int sampleRate) {
        return buildWav(samplesToPcm16(samples), sampleRate, 1, 16);
    }

    /** 重采样 16bit mono PCM */
    public static short[] resamplePcm16(short[] input, int fromRate, int toRate) {
        return resample(input, fromRate, toRate);
    }

    private static short[] resample(short[] input, int fromRate, int toRate) {
        if (fromRate == toRate) {
            return input;
        }
        int outLen = (int) ((long) input.length * toRate / fromRate);
        if (outLen < 1) {
            outLen = 1;
        }
        short[] out = new short[outLen];
        for (int i = 0; i < outLen; i++) {
            double srcPos = (double) i * fromRate / toRate;
            int idx = (int) srcPos;
            if (idx >= input.length - 1) {
                out[i] = input[input.length - 1];
            } else {
                double frac = srcPos - idx;
                out[i] = (short) (input[idx] * (1 - frac) + input[idx + 1] * frac);
            }
        }
        return out;
    }

    /** 将多段 8k 电话 wav 首尾拼接为一段（无句间静音，避免播放中间空白） */
    public static byte[] concatenateWavs(List<byte[]> wavParts) throws IOException {
        if (wavParts == null || wavParts.isEmpty()) {
            throw new IOException("无音频片段");
        }
        if (wavParts.size() == 1) {
            return toTelephony8kMono(wavParts.get(0));
        }
        List<short[]> segments = new ArrayList<>();
        int total = 0;
        for (byte[] part : wavParts) {
            byte[] norm = toTelephony8kMono(part);
            ParsedWav parsed = parseWavPcm(norm);
            short[] samples = pcm16ToSamples(parsed.pcm, parsed.bitsPerSample, parsed.channels);
            if (parsed.sampleRate != TELEPHONY_RATE) {
                samples = resample(samples, parsed.sampleRate, TELEPHONY_RATE);
            }
            if (parsed.channels > 1) {
                samples = downmixToMono(samples, parsed.channels);
            }
            segments.add(samples);
            total += samples.length;
        }
        short[] merged = new short[total];
        int pos = 0;
        for (short[] seg : segments) {
            System.arraycopy(seg, 0, merged, pos, seg.length);
            pos += seg.length;
        }
        return buildWav(samplesToPcm16(merged), TELEPHONY_RATE, 1, 16);
    }

    private static short[] downmixToMono(short[] interleaved, int channels) {
        if (channels <= 1 || interleaved == null || interleaved.length == 0) {
            return interleaved;
        }
        int frames = interleaved.length / channels;
        short[] mono = new short[frames];
        for (int i = 0; i < frames; i++) {
            int sum = 0;
            for (int c = 0; c < channels; c++) {
                sum += interleaved[i * channels + c];
            }
            mono[i] = (short) (sum / channels);
        }
        return mono;
    }

    /** 在 wav 末尾追加静音（句间停顿，毫秒） */
    public static byte[] appendSilenceMs(byte[] wavBytes, int silenceMs) throws IOException {
        if (silenceMs <= 0 || wavBytes.length < 44) {
            return wavBytes;
        }
        byte[] normalized = toTelephony8kMono(wavBytes);
        ByteBuffer buf = ByteBuffer.wrap(normalized).order(ByteOrder.LITTLE_ENDIAN);
        buf.position(44);
        int samples = (normalized.length - 44) / 2;
        short[] all = new short[samples + TELEPHONY_RATE * silenceMs / 1000];
        for (int i = 0; i < samples; i++) {
            all[i] = buf.getShort();
        }
        return buildWav(samplesToPcm16(all), TELEPHONY_RATE, 1, 16);
    }

    /** 定位 WAV data 块起始偏移，全程录音增量读取时使用。 */
    public static int findWavDataOffset(byte[] raw) {
        if (raw == null || raw.length < 44) {
            return 44;
        }
        ByteBuffer buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        buf.position(12);
        while (buf.remaining() >= 8) {
            byte[] tag = new byte[4];
            buf.get(tag);
            int size = buf.getInt();
            if ("data".equals(new String(tag))) {
                return buf.position();
            }
            buf.position(buf.position() + Math.max(0, size));
        }
        return 44;
    }
}
