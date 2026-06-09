package com.aicall.util;

/**
 * 8kHz 电话 PCM 端点检测：连续静音达到阈值视为一句话结束。
 */
public final class TelephonyVadUtil {

    public static final int FRAME_MS = 20;

    private TelephonyVadUtil() {
    }

    /**
     * 是否已说完：有足够语音后，尾部连续静音 >= silenceMs。
     */
    public static boolean isUtteranceComplete(short[] samples, int sampleRate,
                                              int silenceMs, int minSpeechMs, int energyThreshold) {
        if (samples == null || samples.length == 0 || sampleRate <= 0) {
            return false;
        }
        int frameSamples = Math.max(80, sampleRate * FRAME_MS / 1000);
        int silenceFrames = Math.max(1, silenceMs / FRAME_MS);
        int minSpeechFrames = Math.max(1, minSpeechMs / FRAME_MS);

        int speechFrames = 0;
        int trailingSilence = 0;
        for (int i = 0; i < samples.length; i += frameSamples) {
            int end = Math.min(i + frameSamples, samples.length);
            boolean speech = frameRms(samples, i, end) >= energyThreshold;
            if (speech) {
                speechFrames++;
                trailingSilence = 0;
            } else if (speechFrames > 0) {
                trailingSilence++;
            }
        }
        return speechFrames >= minSpeechFrames && trailingSilence >= silenceFrames;
    }

    public static boolean hasAnySpeech(short[] samples, int energyThreshold) {
        if (samples == null || samples.length == 0) {
            return false;
        }
        int frame = 160;
        for (int i = 0; i < samples.length; i += frame) {
            if (frameRms(samples, i, Math.min(i + frame, samples.length)) >= energyThreshold) {
                return true;
            }
        }
        return false;
    }

    private static int frameRms(short[] samples, int from, int to) {
        long sum = 0;
        int n = 0;
        for (int i = from; i < to; i++) {
            sum += (long) samples[i] * samples[i];
            n++;
        }
        if (n == 0) {
            return 0;
        }
        return (int) Math.sqrt((double) sum / n);
    }
}
