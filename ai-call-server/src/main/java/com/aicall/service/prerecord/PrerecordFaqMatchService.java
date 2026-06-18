package com.aicall.service.prerecord;

import com.aicall.entity.PrerecordFaq;
import com.aicall.mapper.PrerecordFaqMapper;
import com.aicall.util.TtsAudioCacheKeyUtil;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class PrerecordFaqMatchService {

    private static final double MIN_SCORE = 2.0;

    private final PrerecordFaqMapper prerecordFaqMapper;
    private final PrerecordClipMemoryService prerecordClipMemoryService;

    public Optional<PrerecordFaq> match(String userText) {
        if (!StringUtils.hasText(userText)) {
            return Optional.empty();
        }
        String norm = TtsAudioCacheKeyUtil.normalizeUserQuestion(userText);
        if (norm.length() < 2) {
            return Optional.empty();
        }
        List<PrerecordFaq> faqs = prerecordClipMemoryService.listHighFreqFaqs();
        PrerecordFaq best = null;
        double bestScore = 0;
        for (PrerecordFaq faq : faqs) {
            double score = score(norm, userText, faq);
            if (score > bestScore) {
                bestScore = score;
                best = faq;
            }
        }
        if (best != null && bestScore >= MIN_SCORE) {
            log.info("[预录匹配] 命中 faqId={} category={} score={} question={}",
                    best.getId(), best.getCategory(), bestScore, abbreviate(userText));
            return Optional.of(best);
        }
        log.debug("[预录匹配] 未命中 question={} bestScore={}", abbreviate(userText), bestScore);
        return Optional.empty();
    }

    public void incrementMatch(Long faqId) {
        if (faqId == null) {
            return;
        }
        prerecordFaqMapper.update(null, new LambdaUpdateWrapper<PrerecordFaq>()
                .eq(PrerecordFaq::getId, faqId)
                .setSql("match_count = IFNULL(match_count, 0) + 1"));
    }

    public void incrementTransfer(Long faqId) {
        if (faqId == null) {
            return;
        }
        prerecordFaqMapper.update(null, new LambdaUpdateWrapper<PrerecordFaq>()
                .eq(PrerecordFaq::getId, faqId)
                .setSql("transfer_count = IFNULL(transfer_count, 0) + 1"));
    }

    private double score(String normUser, String rawUser, PrerecordFaq faq) {
        double score = 0;
        if (StringUtils.hasText(faq.getQuestionNorm()) && normUser.contains(faq.getQuestionNorm())) {
            score += 5;
        }
        if (StringUtils.hasText(faq.getKeywords())) {
            for (String kw : faq.getKeywords().split("[,，]")) {
                String k = kw.trim().toLowerCase(Locale.ROOT);
                if (k.length() >= 2 && (normUser.contains(k) || rawUser.contains(kw.trim()))) {
                    score += 2.5;
                }
            }
        }
        if (StringUtils.hasText(faq.getCategory())) {
            String cat = faq.getCategory().toLowerCase(Locale.ROOT);
            if (normUser.contains(cat) || rawUser.contains(faq.getCategory())) {
                score += 1;
            }
        }
        return score;
    }

    private static String abbreviate(String text) {
        String s = text.trim();
        return s.length() <= 24 ? s : s.substring(0, 24) + "…";
    }
}
