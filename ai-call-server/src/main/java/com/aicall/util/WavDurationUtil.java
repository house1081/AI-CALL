package com.aicall.util;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 解析/校验 PCM wav 时长（FS 显示 0 秒多为 data 块长度与头不一致或采样率不对）。
 */
public final class WavDurationUtil {

    private WavDurationUtil() {
    }

    public static double durationSeconds(byte[] wav) throws IOException {
        if (wav.length < 44) {
            throw new IOException("wav 过短");
        }
        ByteBuffer buf = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);
        if (!readFourCC(buf).equals("RIFF")) {
            throw new IOException("非标准 wav");
        }
        buf.getInt();
        if (!readFourCC(buf).equals("WAVE")) {
            throw new IOException("非标准 wav");
        }
        int sampleRate = 8000;
        int channels = 1;
        int bitsPerSample = 16;
        int dataSize = -1;
        while (buf.remaining() >= 8) {
            String chunk = readFourCC(buf);
            int size = buf.getInt();
            if (size < 0 || size > buf.remaining()) {
                break;
            }
            if ("fmt ".equals(chunk) && size >= 16) {
                buf.getShort();
                channels = buf.getShort();
                sampleRate = buf.getInt();
                buf.getInt();
                buf.getShort();
                bitsPerSample = buf.getShort();
                int skip = size - 16;
                if (skip > 0) {
                    buf.position(buf.position() + skip);
                }
            } else if ("data".equals(chunk)) {
                dataSize = size;
                break;
            } else {
                buf.position(buf.position() + size + (size & 1));
            }
        }
        if (dataSize <= 0) {
            throw new IOException("无 data 块");
        }
        int byteRate = sampleRate * channels * bitsPerSample / 8;
        if (byteRate <= 0) {
            throw new IOException("无效 byteRate");
        }
        return (double) dataSize / byteRate;
    }

    public static double durationSeconds(Path path) throws IOException {
        return durationSeconds(Files.readAllBytes(path));
    }

    private static String readFourCC(ByteBuffer buf) {
        byte[] b = new byte[4];
        buf.get(b);
        return new String(b);
    }
}
