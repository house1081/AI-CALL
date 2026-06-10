package com.aicall.service;

import com.aicall.config.AiVoiceProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 根据客户话术动态选择 CosyVoice instruction 与语速（看人说话）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TtsProsodyService {

    private final AiVoiceProperties aiVoiceProperties;

    public void bindForUserUtterance(String userText) {
        if (!aiVoiceProperties.isTtsDynamicProsodyEnabled()) {
            return;
        }
        String user = userText != null ? userText.trim() : "";
        String base = aiVoiceProperties.getTtsInstruction();
        double rate = aiVoiceProperties.getTtsSpeechRate();
        String mood = "neutral";

        if (isHesitant(user)) {
            mood = "hesitant";
            rate = Math.max(0.85, rate - 0.05);
            base = base + " 客户有顾虑，语气再沉稳一些，语速略慢，多安抚、少推销。";
        } else if (isProductInquiry(user)) {
            mood = "inquiry";
            rate = Math.min(0.98, rate + 0.03);
            base = base + " 客户在问产品，语气专业热情，介绍优势时可略加重、稍快一点。";
        } else if (isCold(user)) {
            mood = "cold";
            rate = Math.max(0.85, rate - 0.04);
            base = base + " 客户较冷淡，语调柔和、语速放缓，不要激进，保持真诚。";
        }

        TtsProsodyContext.bind(new TtsProsodyContext.Prosody(base, rate, mood));
        if (!"neutral".equals(mood)) {
            log.debug("[TTS韵律] mood={} rate={}", mood, rate);
        }
    }

    public void clear() {
        TtsProsodyContext.clear();
    }

    private static boolean isCold(String user) {
        if (!StringUtils.hasText(user) || user.length() > 20) {
            return user != null && (user.equals("嗯") || user.equals("哦") || user.equals("啊")
                    || user.equals("喂") || user.equals("。"));
        }
        return user.contains("随便") || user.contains("不用") || user.contains("没兴趣")
                || user.contains("再说吧") || user.contains("忙着") || user.contains("挂了")
                || user.equals("嗯") || user.equals("哦") || user.equals("啊");
    }

    private static boolean isProductInquiry(String user) {
        return user.contains("额度") || user.contains("利率") || user.contains("利息")
                || user.contains("怎么贷") || user.contains("多少钱") || user.contains("放款")
                || user.contains("多少万") || user.contains("手续") || user.contains("征信");
    }

    private static boolean isHesitant(String user) {
        return user.contains("考虑") || user.contains("想想") || user.contains("不太")
                || user.contains("担心") || user.contains("靠谱") || user.contains("怕")
                || user.contains("会不会") || user.contains("安全吗");
    }
}
