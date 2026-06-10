package com.aicall.service;

import lombok.Getter;

/** 单轮对话 TTS 韵律上下文（ThreadLocal，供 CosyVoice 读取） */
public final class TtsProsodyContext {

    private static final ThreadLocal<Prosody> CURRENT = new ThreadLocal<>();

    private TtsProsodyContext() {
    }

    public static void bind(Prosody prosody) {
        CURRENT.set(prosody);
    }

    public static Prosody current() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }

    @Getter
    public static final class Prosody {
        private final String instruction;
        private final double speechRate;
        private final String mood;

        public Prosody(String instruction, double speechRate, String mood) {
            this.instruction = instruction;
            this.speechRate = speechRate;
            this.mood = mood;
        }
    }
}
