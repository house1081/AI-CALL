package com.aicall.common;

/**
 * CosyVoice / 本地 TTS 合成失败（含 428/429 限流），上层应播结束语并挂断。
 */
public class TtsSynthesisException extends RuntimeException {

    private final boolean rateLimited;

    public TtsSynthesisException(String message) {
        this(message, false);
    }

    public TtsSynthesisException(String message, boolean rateLimited) {
        super(message);
        this.rateLimited = rateLimited;
    }

    public TtsSynthesisException(String message, Throwable cause, boolean rateLimited) {
        super(message, cause);
        this.rateLimited = rateLimited;
    }

    public boolean isRateLimited() {
        return rateLimited;
    }

    public static boolean isRateLimitedMessage(String msg) {
        if (msg == null) {
            return false;
        }
        String m = msg.toLowerCase();
        return m.contains("428") || m.contains("429") || m.contains("throttling")
                || m.contains("rate limit") || m.contains("too many requests");
    }

    /** 从 CompletionException / ExecutionException 等包装中取出 TTS 异常 */
    public static TtsSynthesisException unwrap(Throwable t) {
        for (Throwable cur = t; cur != null; cur = cur.getCause()) {
            if (cur instanceof TtsSynthesisException e) {
                return e;
            }
        }
        return null;
    }
}
