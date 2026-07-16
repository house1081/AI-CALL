package com.aicall.dto;

/** 浏览器麦克风 ASR 结果（含失败原因码，供训练页展示） */
public record AsrBrowserResult(String text, String failureCode) {

    public static AsrBrowserResult ok(String text) {
        return new AsrBrowserResult(text != null ? text.trim() : "", null);
    }

    public static AsrBrowserResult fail(String code) {
        return new AsrBrowserResult("", code);
    }

    public boolean hasText() {
        return text != null && !text.isBlank();
    }
}
