package com.aicall.service;

/** VAD 未检测到客户有效语音，不应送 ASR（避免静音被识别成「嗯」） */
public class NoSpeechDetectedException extends java.io.IOException {

    public NoSpeechDetectedException(String uuid) {
        super("NO_SPEECH_DETECTED:" + uuid);
    }

    public static boolean isNoSpeech(Throwable t) {
        if (t == null) {
            return false;
        }
        String msg = t.getMessage();
        return msg != null && msg.contains("NO_SPEECH_DETECTED");
    }
}
