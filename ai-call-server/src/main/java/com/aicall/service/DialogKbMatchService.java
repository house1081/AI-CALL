package com.aicall.service;

import com.aicall.common.DialogSlotHelper;
import com.aicall.common.DialogTrainingIntentRouter;
import com.aicall.common.ForcedHangupRules;
import com.aicall.config.DialogRagProperties;
import com.aicall.dto.DialogRagHitDto;
import com.aicall.dto.DialogRagRetrieveResult;
import com.aicall.dto.KbMatchCandidate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 智能预录统一 FAQ 匹配：关键词 + 向量多候选，优先可播放录音，避免「命中文本却无 wav」。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DialogKbMatchService {

    private final DialogRagRetrievalService dialogRagRetrievalService;
    private final DialogRagProperties dialogRagProperties;
    private final KbAnswerWavCacheService kbAnswerWavCacheService;

    /**
     * 按优先级排序的 FAQ 候选：可播录音优先，其次高分向量/关键词。
     */
    public List<KbMatchCandidate> rankCandidates(String userText, int kbId) {
        if (!StringUtils.hasText(userText)) {
            return List.of();
        }
        Map<String, KbMatchCandidate> dedup = new LinkedHashMap<>();

        DialogRagRetrieveResult kw = dialogRagRetrievalService.tryKeywordDirectAnswer(userText, kbId);
        KbMatchCandidate keywordCandidate = null;
        if (kw.isKeywordMatched() && StringUtils.hasText(kw.getDirectAnswerText())) {
            keywordCandidate = buildKeywordCandidate(kw, kbId);
            putCandidate(dedup, keywordCandidate);
        }
        // 关键词已命中且可播录音时跳过向量 embedding（省 100~800ms）
        if (keywordCandidate != null && keywordCandidate.isPlayable()) {
            return sortCandidates(dedup, kbId, userText);
        }

        DialogRagRetrieveResult rag = dialogRagRetrievalService.retrieveForPrerecord(userText, kbId);
        if (rag.isKeywordMatched() && StringUtils.hasText(rag.getDirectAnswerText())
                && !dedup.containsKey(normalizeKey(rag.getDirectAnswerText()))) {
            putCandidate(dedup, buildKeywordCandidate(rag, kbId));
        }
        for (DialogRagHitDto hit : rag.getHits()) {
            if (!shouldConsiderVectorHit(userText, rag, hit, kbId)) {
                continue;
            }
            putCandidate(dedup, buildVectorCandidate(hit, kbId, rag.isKeywordMatched()));
        }

        return sortCandidates(dedup, kbId, userText);
    }

    private List<KbMatchCandidate> sortCandidates(Map<String, KbMatchCandidate> dedup, int kbId, String userText) {
        List<KbMatchCandidate> ranked = new ArrayList<>(dedup.values());
        ranked.sort(Comparator
                .comparing(KbMatchCandidate::isPlayable).reversed()
                .thenComparing(KbMatchCandidate::isKeywordMatched).reversed()
                .thenComparing(KbMatchCandidate::getScore, Comparator.reverseOrder()));
        if (!ranked.isEmpty()) {
            log.info("[KB匹配] kb={} user={} 候选={} 可播={}",
                    kbId, truncate(userText), ranked.size(),
                    ranked.stream().filter(KbMatchCandidate::isPlayable).count());
        }
        return ranked;
    }

    public boolean shouldPlay(KbMatchCandidate candidate, String userText) {
        return shouldPlay(candidate, userText, null, null);
    }

    public boolean shouldPlay(KbMatchCandidate candidate, String userText,
                              String lastAssistantText, String currentStep) {
        if (candidate == null || !candidate.hasReplyText()) {
            return false;
        }
        if (StringUtils.hasText(currentStep)
                && DialogTrainingIntentRouter.shouldDeferKeywordFaqToMainFlow(
                userText, lastAssistantText, currentStep)) {
            return false;
        }
        if (shouldSuppressTrainingFaq(candidate, userText)) {
            return false;
        }
        if (candidate.isKeywordMatched()) {
            return true;
        }
        if (DialogSlotHelper.isFollowUpComplaint(userText)) {
            return candidate.getScore() >= dialogRagProperties.getMinRetrieveScore();
        }
        if (ForcedHangupRules.isIdentityInquiry(userText)) {
            return candidate.isKeywordMatched();
        }
        if (candidate.isPlayable()
                && candidate.getScore() >= dialogRagProperties.getPlayableMinScore()) {
            return DialogSlotHelper.isExplicitCustomerQuestion(userText)
                    || ForcedHangupRules.isHearingIssue(userText)
                    || candidate.getScore() >= dialogRagProperties.getHighConfidenceFaqScore();
        }
        if (candidate.getScore() >= dialogRagProperties.getHighConfidenceFaqScore()) {
            return true;
        }
        return DialogSlotHelper.isExplicitCustomerQuestion(userText)
                && candidate.getScore() >= dialogRagProperties.getDirectAnswerScore();
    }

    private static boolean shouldSuppressTrainingFaq(KbMatchCandidate candidate, String userText) {
        String reply = candidate.getReplyText();
        if (!StringUtils.hasText(reply)) {
            return false;
        }
        if (reply.contains("你现在不需要也没关系")
                && DialogTrainingIntentRouter.shouldSuppressNoNeedFaq(userText, 70)) {
            return true;
        }
        if (reply.contains("可以不结清按揭尾款")
                && DialogTrainingIntentRouter.shouldSuppressMortgageTailFaq(userText, 73)) {
            return true;
        }
        if (reply.contains("线上线下都有")
                && DialogTrainingIntentRouter.shouldSuppressOnlineOfflineFaq(userText, 110)) {
            return true;
        }
        if (reply.contains("合同都是和银行签订")
                && DialogTrainingIntentRouter.isManyOnlineLoanApplications(userText)) {
            return true;
        }
        if (reply.contains("您在哪个区")
                && DialogTrainingIntentRouter.shouldSuppressCompanyAddressFaq(userText, 16)) {
            return true;
        }
        return false;
    }

    private boolean shouldConsiderVectorHit(String userText, DialogRagRetrieveResult rag,
                                            DialogRagHitDto hit, int kbId) {
        if (hit == null || !StringUtils.hasText(hit.getStandardAnswer())) {
            return false;
        }
        if (hit.getWeightedScore() < dialogRagProperties.getMinRetrieveScore() * 0.92) {
            return false;
        }
        if (rag.isKeywordMatched()) {
            return hit.getWeightedScore() >= dialogRagProperties.getDirectAnswerScore();
        }
        if (DialogSlotHelper.isFollowUpComplaint(userText)) {
            return true;
        }
        if (ForcedHangupRules.isIdentityInquiry(userText)) {
            return rag.isKeywordMatched();
        }
        if (hit.getWeightedScore() >= dialogRagProperties.getHighConfidenceFaqScore()) {
            return true;
        }
        if (DialogSlotHelper.isExplicitCustomerQuestion(userText)
                && hit.getWeightedScore() >= dialogRagProperties.getDirectAnswerScore()) {
            return hit.getQaId() != null
                    && StringUtils.hasText(kbAnswerWavCacheService.resolveByQaId(hit.getQaId()));
        }
        return false;
    }

    private KbMatchCandidate buildKeywordCandidate(DialogRagRetrieveResult rag, int kbId) {
        String reply = rag.getDirectAnswerText().trim();
        Integer ruleId = rag.getKeywordRuleId();
        String wav = resolveWavPath(ruleId, null, reply, kbId);
        return KbMatchCandidate.builder()
                .replyText(reply)
                .keywordRuleId(ruleId)
                .qaId(ruleId)
                .wavPath(wav)
                .playable(StringUtils.hasText(wav))
                .source("keyword")
                .score(10.0)
                .keywordMatched(true)
                .build();
    }

    private KbMatchCandidate buildVectorCandidate(DialogRagHitDto hit, int kbId, boolean keywordContext) {
        String reply = hit.getStandardAnswer().trim();
        String wav = hit.getQaId() != null ? kbAnswerWavCacheService.resolveByQaId(hit.getQaId()) : null;
        return KbMatchCandidate.builder()
                .replyText(reply)
                .qaId(hit.getQaId())
                .wavPath(wav)
                .playable(StringUtils.hasText(wav))
                .source(keywordContext ? "vector+keyword" : "vector")
                .score(hit.getWeightedScore())
                .keywordMatched(false)
                .build();
    }

    private String resolveWavPath(Integer qaId, String explicitWav, String answerText, int kbId) {
        if (StringUtils.hasText(explicitWav)) {
            String cached = kbAnswerWavCacheService.resolveExplicit(explicitWav);
            if (StringUtils.hasText(cached)) {
                return cached;
            }
        }
        if (qaId != null) {
            String byQa = kbAnswerWavCacheService.resolveByQaId(qaId);
            if (StringUtils.hasText(byQa)) {
                return byQa;
            }
        }
        return kbAnswerWavCacheService.resolveByAnswerText(kbId, answerText);
    }

    private boolean isExistingWav(String wavPath) {
        return StringUtils.hasText(kbAnswerWavCacheService.resolveExplicit(wavPath));
    }

    private static void putCandidate(Map<String, KbMatchCandidate> dedup, KbMatchCandidate c) {
        if (c == null || !c.hasReplyText()) {
            return;
        }
        String key = normalizeKey(c.getReplyText());
        KbMatchCandidate existing = dedup.get(key);
        if (existing == null || candidateBetter(c, existing)) {
            dedup.put(key, c);
        }
    }

    private static boolean candidateBetter(KbMatchCandidate a, KbMatchCandidate b) {
        if (a.isPlayable() != b.isPlayable()) {
            return a.isPlayable();
        }
        if (a.isKeywordMatched() != b.isKeywordMatched()) {
            return a.isKeywordMatched();
        }
        return a.getScore() > b.getScore();
    }

    private static String normalizeKey(String text) {
        return text.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    private static String truncate(String s) {
        return s.length() > 32 ? s.substring(0, 32) + "..." : s;
    }
}
