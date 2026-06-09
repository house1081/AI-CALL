package com.aicall.common;

public final class AiModelProvider {
    public static final String OLLAMA = "ollama";
    public static final String QWEN = "qwen";
    public static final String WENXIN = "wenxin";

    public static boolean isValid(String provider) {
        return OLLAMA.equals(provider) || QWEN.equals(provider) || WENXIN.equals(provider);
    }

    private AiModelProvider() {}
}
