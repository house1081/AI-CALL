package com.aicall.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Slf4j
@Component
@RequiredArgsConstructor
public class AiVoiceStartupLogger implements ApplicationRunner {

    private final AiVoiceProperties aiVoiceProperties;

    @Override
    public void run(ApplicationArguments args) {
        if (!aiVoiceProperties.isEnabled() || aiVoiceProperties.isStartupQuiet()) {
            log.debug("ai-voice 已启用 ttsMode={} voice={} rtpPtime={}ms streamTts={} streamAsr={}",
                    aiVoiceProperties.getTtsMode(),
                    aiVoiceProperties.getTtsVoice(),
                    aiVoiceProperties.getRtpPacketizationMs(),
                    aiVoiceProperties.isTtsStreamEnabled(),
                    aiVoiceProperties.isAsrStreamEnabled());
            return;
        }
        log.info("【语音】ai-voice 已启用 ttsMode={} CosyVoice音色={}",
                aiVoiceProperties.getTtsMode(), aiVoiceProperties.getTtsVoice());
        if (!StringUtils.hasText(aiVoiceProperties.getPlaybackBaseUrl())) {
            log.warn("【语音】未配置 playback-base-url");
        }
    }
}
