package com.aicall.service;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.util.StringUtils;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 连通性测试：需本地已配置 DashScope Key，且 target/asr-test-speech.wav 存在（由脚本生成）。
 */
@SpringBootTest
class AsrRecognitionLiveTest {

    @Autowired
    private AsrRecognitionService asrRecognitionService;

    @Test
    void telephonyRecognizeFromSampleWav() throws Exception {
        Path wav = Path.of("target/asr-test-speech.wav");
        Assumptions.assumeTrue(Files.exists(wav), "跳过：无测试 wav，请先运行 scripts/gen-asr-test-wav.ps1");
        String text = asrRecognitionService.recognize(wav);
        System.out.println("[ASR-LIVE] 电话识别结果: " + text);
        Assumptions.assumeTrue(StringUtils.hasText(text), "ASR 返回空（检查 Key/网络/音频）");
    }

    @Test
    void browserRecognizeFromSampleWav() throws Exception {
        Path wav = Path.of("target/asr-test-browser.wav");
        Assumptions.assumeTrue(Files.exists(wav), "跳过：无浏览器测试 wav");
        var result = asrRecognitionService.recognizeBrowserMicResult(wav);
        System.out.println("[ASR-LIVE] 浏览器识别: text=" + result.text() + " err=" + result.failureCode());
        Assumptions.assumeTrue(result.hasText(), "浏览器 ASR 失败: " + result.failureCode());
    }
}
