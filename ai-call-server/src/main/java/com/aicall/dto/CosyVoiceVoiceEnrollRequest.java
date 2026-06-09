package com.aicall.dto;

import lombok.Data;

@Data
public class CosyVoiceVoiceEnrollRequest {
    /** 音色名前缀，字母数字，最多 10 位 */
    private String prefix;
    /** 10~20 秒参考音频公网 URL */
    private String audioUrl;
    /** 可选：zh / en 等，默认 zh */
    private String languageHint;
}
