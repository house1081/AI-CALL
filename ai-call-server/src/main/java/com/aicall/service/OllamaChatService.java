package com.aicall.service;

import com.aicall.common.BizException;
import com.aicall.common.DialogSlotHelper;
import com.aicall.common.DialogTrainingIntentRouter;
import com.aicall.common.ForcedHangupRules;
import com.aicall.common.HangupType;
import com.aicall.config.AiVoiceProperties;
import com.aicall.config.DialogRagProperties;
import com.aicall.dto.*;
import com.aicall.util.LlmStreamingSentenceBuffer;
import com.aicall.util.OralScriptNormalizer;
import com.aicall.util.SpeakTextLimiter;
import com.aicall.entity.AiModelConfig;
import com.aicall.entity.AiPrompt;
import com.aicall.mapper.AiPromptMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.function.Consumer;

@Slf4j
@Service
@RequiredArgsConstructor
public class OllamaChatService {

    private final AiModelConfigService aiModelConfigService;
    private final LlmInvokeService llmInvokeService;
    private final AiPromptMapper aiPromptMapper;
    private final ObjectMapper objectMapper;
    private final ForcedHangupService forcedHangupService;
    private final IntentLevelService intentLevelService;
    private final AiVoiceProperties aiVoiceProperties;
    private final CallDialogPersistService callDialogPersistService;
    private final DialogRagRetrievalService dialogRagRetrievalService;
    private final DialogRagProperties dialogRagProperties;
    private final DialogMainFlowService dialogMainFlowService;
    private final DialogCallContextService dialogCallContextService;
    private final CallContextCacheService callContextCacheService;

    public AiPrompt activePrompt() {
        AiPrompt p = aiPromptMapper.selectOne(
                new LambdaQueryWrapper<AiPrompt>().eq(AiPrompt::getIsActive, 1).last("LIMIT 1"));
        if (p == null) {
            throw new BizException("未配置启用的 AI 话术，请先在管理后台设置");
        }
        return p;
    }

    public Map<String, Object> configInfo() {
        AiModelConfig cfg = aiModelConfigService.requireActive();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("configName", cfg.getConfigName());
        m.put("provider", cfg.getProvider());
        m.put("baseUrl", cfg.getBaseUrl());
        m.put("model", cfg.getModelName());
        m.put("maxTokens", cfg.getMaxTokens());
        m.put("maxHistoryRounds", cfg.getMaxHistoryRounds());
        return m;
    }

    public Map<String, Object> healthCheck() {
        AiModelConfig cfg = aiModelConfigService.requireActive();
        return llmInvokeService.healthCheck(cfg);
    }

    public AiChatResponse chat(AiChatRequest req) {
        return chat(req, null);
    }

    /**
     * @param onSentence 流式模式下每凑满一句完整话术时回调（用于边生成边 TTS）
     */
    public AiChatResponse chat(AiChatRequest req, Consumer<String> onSentence) {
        AiModelConfig modelCfg = aiModelConfigService.requireActive();
        DialogCallContext ctx = req.getCallRecordId() != null
                ? callContextCacheService.get(req.getCallRecordId())
                : dialogCallContextService.resolve(req.getCallRecordId());
        AiPrompt prompt = ctx.getPrompt() != null ? ctx.getPrompt() : activePrompt();
        int kbId = ctx.hasKb() ? ctx.getKbId() : DialogCallContextService.DEFAULT_KB_ID;
        long start = System.currentTimeMillis();

        if (Boolean.TRUE.equals(req.getFirstTurn())) {
            if (req.getCallRecordId() != null || StringUtils.hasText(req.getTrainSessionId())) {
                forcedHangupService.initSession(req.getCallRecordId(), req.getTrainSessionId(), req.getFsUuid());
            }
            if (req.getCallRecordId() != null && dialogMainFlowService.isEnabled(kbId)) {
                dialogMainFlowService.initCall(req.getCallRecordId(), kbId);
            }
            AiChatResponse r = new AiChatResponse();
            String opening = resolveOpeningReply(req, ctx, kbId, prompt);
            r.setReply(opening);
            r.setModel(displayModel(modelCfg));
            r.setFromOpening(true);
            r.setLatencyMs(System.currentTimeMillis() - start);
            fillHangupMeta(r, req, HangupDecision.none(0, 0));
            return r;
        }

        if (!StringUtils.hasText(req.getUserText())) {
            throw new BizException("userText 不能为空");
        }

        if (ForcedHangupRules.isUserFarewell(req.getUserText())) {
            return buildFarewellResponse(req, modelCfg, start);
        }

        String lastAi = lastAssistantText(req.getHistory());
        if (ForcedHangupRules.declinesFundingNeed(req.getUserText(), lastAi)) {
            log.info("[对话LLM] 客户拒绝资金/贷款需求 recordId={} user={}",
                    req.getCallRecordId(),
                    req.getUserText().length() > 24 ? req.getUserText().substring(0, 24) + "..." : req.getUserText());
            return buildFarewellResponse(req, modelCfg, start);
        }

        if (!aiVoiceProperties.isDialogLlmPrimary()) {
            String quick = quickReplyForUser(req);
            if (quick != null) {
                return buildQuickReply(req, quick, modelCfg, start);
            }
        } else if (req.getCallRecordId() != null && DialogSlotHelper.isStatingLoanAmount(req.getUserText())) {
            String amountSlot = quickReplyForUser(req);
            if (StringUtils.hasText(amountSlot)) {
                log.info("[对话LLM] 外呼额度槽位优先 recordId={} user={}",
                        req.getCallRecordId(),
                        req.getUserText().length() > 24
                                ? req.getUserText().substring(0, 24) + "..." : req.getUserText());
                return buildQuickReply(req, amountSlot, modelCfg, start);
            }
        }

        HangupDecision pre = HangupDecision.none(0, 0);
        if (req.getCallRecordId() != null || StringUtils.hasText(req.getTrainSessionId())) {
            pre = forcedHangupService.evaluateBeforeAi(
                    req.getCallRecordId(), req.getTrainSessionId(),
                    req.getUserText(), req.getBusinessProbeThisTurn());
            if (pre.isShouldHangup()) {
                if (HangupType.FORCE_REFUSE.equals(pre.getHangupType())
                        && (shouldDeferForcedHangup(req.getUserText())
                        || ForcedHangupRules.isCooperativeAnswer(req.getUserText())
                        || ForcedHangupRules.hasBusinessIntent(req.getUserText()))) {
                    pre = HangupDecision.none(pre.getElapsedSeconds(), pre.getInvalidChatRounds());
                } else {
                    return buildHangupResponse(pre, start, modelCfg);
                }
            }
        }

        if (req.getCallRecordId() != null && DialogSlotHelper.shouldBypassRag(req.getUserText())) {
            dialogMainFlowService.ensureInit(req.getCallRecordId(), kbId);
            if (dialogMainFlowService.isEnabled(kbId)) {
                String mainLine = dialogMainFlowService.nextMainLineAfterUser(
                        req.getCallRecordId(), req.getUserText());
                if (StringUtils.hasText(mainLine)) {
                    log.info("[主线] 问候/语气词接主线 recordId={} kb={} user={}",
                            req.getCallRecordId(), kbId,
                            req.getUserText().length() > 24
                                    ? req.getUserText().substring(0, 24) + "..." : req.getUserText());
                    return buildQuickReply(req, trimReply(mainLine), modelCfg, start, false);
                }
            }
            log.info("[对话LLM] 问候/语气词不走RAG recordId={} user={}",
                    req.getCallRecordId(),
                    req.getUserText().length() > 24
                            ? req.getUserText().substring(0, 24) + "..." : req.getUserText());
            return buildQuickReply(req, trimReply(ForcedHangupRules.hearingFallbackReply()),
                    modelCfg, start);
        }

        if (dialogRagProperties.isEnabled()
                && ForcedHangupRules.isCompanyOrAddressInquiry(req.getUserText())) {
            DialogRagRetrieveResult keywordHit = dialogRagRetrievalService.tryKeywordDirectAnswer(
                    req.getUserText(), kbId);
            if (keywordHit.isDirectAnswer() && StringUtils.hasText(keywordHit.getDirectAnswerText())) {
                log.info("[RAG] 公司/地址关键词直出 recordId={} kb={} user={}",
                        req.getCallRecordId(), kbId,
                        req.getUserText().length() > 24
                                ? req.getUserText().substring(0, 24) + "..." : req.getUserText());
                return buildQuickReply(req, trimReply(keywordHit.getDirectAnswerText()),
                        modelCfg, start, true);
            }
        }

        String intentQuick = fallbackForUserUtterance(req.getUserText());
        if (StringUtils.hasText(intentQuick)) {
            log.info("[对话LLM] 身份/服务快答 recordId={} trainSession={} user={}",
                    req.getCallRecordId(), req.getTrainSessionId(),
                    req.getUserText().length() > 24
                            ? req.getUserText().substring(0, 24) + "..." : req.getUserText());
            return buildQuickReply(req, trimReply(intentQuick), modelCfg, start, true);
        }

        if (req.getCallRecordId() != null && dialogMainFlowService.isEnabled(kbId)
                && DialogSlotHelper.shouldPreferMainFlowAdvance(req.getUserText())) {
            String mainLine = dialogMainFlowService.nextMainLineAfterUser(
                    req.getCallRecordId(), req.getUserText(), lastAi);
            if (StringUtils.hasText(mainLine)) {
                log.info("[主线] 快答 recordId={} kb={} step={} user={}",
                        req.getCallRecordId(), kbId, dialogMainFlowService.currentStep(req.getCallRecordId()),
                        req.getUserText().length() > 16 ? req.getUserText().substring(0, 16) + "…" : req.getUserText());
                return buildQuickReply(req, trimReply(mainLine), modelCfg, start, false);
            }
        }

        if (req.getCallRecordId() != null && dialogMainFlowService.isEnabled(kbId)) {
            String step = dialogMainFlowService.currentStep(req.getCallRecordId());
            if (DialogTrainingIntentRouter.shouldDeferKeywordFaqToMainFlow(req.getUserText(), lastAi, step)) {
                String mainLine = dialogMainFlowService.nextMainLineAfterUser(
                        req.getCallRecordId(), req.getUserText(), lastAi);
                if (StringUtils.hasText(mainLine)) {
                    log.info("[主线] 语境槽位优先 recordId={} kb={} step={} user={}",
                            req.getCallRecordId(), kbId, step,
                            req.getUserText().length() > 16 ? req.getUserText().substring(0, 16) + "…" : req.getUserText());
                    return buildQuickReply(req, trimReply(mainLine), modelCfg, start, false);
                }
            }
        }

        DialogRagRetrieveResult rag = null;
        if (dialogRagProperties.isEnabled()) {
            rag = dialogRagRetrievalService.retrieve(req.getUserText(), kbId);
            AiChatResponse ragReply = tryBuildRagDirectReply(req, rag, kbId, modelCfg, start);
            if (ragReply != null) {
                return ragReply;
            }
            if (rag.isNoMatchFallback() && !dialogMainFlowService.isEnabled(kbId)) {
                log.info("[RAG] 无匹配严格兜底 recordId={} kb={} ms={}",
                        req.getCallRecordId(), kbId, rag.getRetrieveMs());
                return buildQuickReply(req, trimReply(dialogRagRetrievalService.noMatchFallbackText()),
                        modelCfg, start, false);
            }
        }

        if (req.getCallRecordId() != null) {
            String fallback = fallbackForUserUtterance(req.getUserText());
            if (fallback != null) {
                log.info("[对话LLM] 外呼固定快答 recordId={} user={}",
                        req.getCallRecordId(),
                        req.getUserText().length() > 24
                                ? req.getUserText().substring(0, 24) + "..." : req.getUserText());
                return buildQuickReply(req, fallback, modelCfg, start);
            }
            String instant = quickReplyForUser(req);
            if (instant != null && preferInstantSlotReply(req.getUserText())) {
                log.info("[对话LLM] 外呼槽位快答 recordId={} user={}",
                        req.getCallRecordId(),
                        req.getUserText().length() > 24
                                ? req.getUserText().substring(0, 24) + "..." : req.getUserText());
                return buildQuickReply(req, instant, modelCfg, start);
            }
        }

        if (req.getCallRecordId() != null && dialogMainFlowService.isEnabled(kbId)
                && !DialogSlotHelper.prefersContextualLlmReply(req.getUserText(),
                rag != null && rag.isHasPositiveMatch())) {
            String mainLine = dialogMainFlowService.nextMainLineAfterUser(req.getCallRecordId(), req.getUserText());
            if (StringUtils.hasText(mainLine)) {
                log.info("[主线] 循序播报 recordId={} kb={} step={} user={}",
                        req.getCallRecordId(), kbId, dialogMainFlowService.currentStep(req.getCallRecordId()),
                        req.getUserText().length() > 24 ? req.getUserText().substring(0, 24) + "..." : req.getUserText());
                return buildQuickReply(req, trimReply(mainLine), modelCfg, start, false);
            }
        } else if (req.getCallRecordId() != null && dialogMainFlowService.isEnabled(kbId)
                && DialogSlotHelper.prefersContextualLlmReply(req.getUserText(),
                rag != null && rag.isHasPositiveMatch())) {
            log.info("[主线] 客户提问/FAQ命中，跳过主线推进 recordId={} user={}",
                    req.getCallRecordId(),
                    req.getUserText().length() > 24 ? req.getUserText().substring(0, 24) + "..." : req.getUserText());
        }

        String system = buildSystemPrompt(prompt, pre.getElapsedSeconds(), req.getHistory(),
                req.getUserText(), rag, req.getCallRecordId());
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", system));
        appendHistory(messages, req.getHistory(), modelCfg);
        messages.add(Map.of("role", "user", "content", req.getUserText().trim()));

        AiModelConfig llmCfg = cappedModelCfg(modelCfg);
        int histSize = req.getHistory() != null ? req.getHistory().size() : 0;
        boolean stream = aiVoiceProperties.isDialogLlmStream();
        long llmStart = System.currentTimeMillis();
        log.info("[对话LLM] {}调用大模型 recordId={} historyMsgs={} maxTokens={} user={}",
                stream ? "流式" : "",
                req.getCallRecordId(), histSize, llmCfg.getMaxTokens(),
                req.getUserText().length() > 40 ? req.getUserText().substring(0, 40) + "..." : req.getUserText());

        String rawReply;
        try {
            if (stream) {
                LlmStreamingSentenceBuffer sentenceBuf = new LlmStreamingSentenceBuffer(
                        aiVoiceProperties.getMaxSpeakChars(),
                        aiVoiceProperties.getStreamTtsFirstChunkChars());
                rawReply = llmInvokeService.chatStreaming(messages, llmCfg, delta -> {
                    for (String sentence : sentenceBuf.feed(delta)) {
                        if (onSentence != null) {
                            onSentence.accept(sentence);
                        }
                    }
                });
                String tail = sentenceBuf.flushRemainder();
                if (onSentence != null && StringUtils.hasText(tail)) {
                    onSentence.accept(tail);
                }
            } else {
                rawReply = llmInvokeService.chat(messages, llmCfg);
            }
        } catch (Exception e) {
            log.warn("[对话LLM] 调用失败 recordId={}: {}", req.getCallRecordId(), e.getMessage());
            if (req.getCallRecordId() != null) {
                return buildQuickReply(req, resolveLiveCallFallbackText(req), modelCfg, start);
            }
            if (e instanceof BizException be) {
                throw be;
            }
            throw new BizException("大模型调用失败: " + e.getMessage());
        }
        log.info("[对话LLM] 完成 recordId={} 耗时{}ms 字数={}",
                req.getCallRecordId(), System.currentTimeMillis() - llmStart,
                rawReply != null ? rawReply.length() : 0);
        String cleaned = forcedHangupService.stripHangupMarkers(rawReply);
        cleaned = polishReply(req, cleaned);
        cleaned = ensureSubstantiveReply(req, cleaned);
        if (req.getCallRecordId() != null || StringUtils.hasText(req.getTrainSessionId())) {
            forcedHangupService.afterAiReply(req.getCallRecordId(), req.getTrainSessionId(), cleaned);
        }

        DialogSlotHelper.Slots slots = DialogSlotHelper.extract(req.getHistory(), req.getUserText());
        boolean slotsSayEnd = DialogSlotHelper.shouldEndCall(req.getUserText(), slots, req.getHistory());
        boolean endCall = !aiVoiceProperties.isDialogLlmPrimary() && slotsSayEnd;
        String finalReply = StringUtils.hasText(cleaned) ? trimReply(cleaned) : "";
        if (!StringUtils.hasText(finalReply) && req.getCallRecordId() != null) {
            finalReply = trimReply(ForcedHangupRules.knowledgeNoMatchFallbackReply());
            log.info("[LLM] 应答为空，使用知识库无匹配兜底 recordId={}", req.getCallRecordId());
        }
        if (endCall) {
            finalReply = DialogSlotHelper.goodbyeReply(req.getHistory());
        } else if (aiVoiceProperties.isDialogLlmPrimary() && slotsSayEnd && !StringUtils.hasText(finalReply)) {
            finalReply = DialogSlotHelper.goodbyeReply(req.getHistory());
        }

        AiChatResponse r = new AiChatResponse();
        r.setReply(finalReply);
        boolean hangup = endCall || (aiVoiceProperties.isDialogLlmPrimary() && slotsSayEnd);
        r.setShouldHangup(hangup);
        if (hangup) {
            r.setEndWords(finalReply);
            r.setHangupTriggered(true);
        }
        r.setBusinessProbeNext(ForcedHangupRules.aiReplyIsBusinessProbe(cleaned));
        r.setModel(displayModel(modelCfg));
        r.setFromOpening(false);
        r.setLatencyMs(System.currentTimeMillis() - start);
        fillHangupMeta(r, req, pre);
        return r;
    }

    private AiChatResponse buildHangupResponse(HangupDecision decision, long start, AiModelConfig modelCfg) {
        String endWords = StringUtils.hasText(decision.getEndWords())
                ? decision.getEndWords() : ForcedHangupRules.endWordsFor(decision.getHangupType());
        AiChatResponse r = new AiChatResponse();
        r.setReply(endWords);
        r.setShouldHangup(true);
        r.setHangupType(decision.getHangupType());
        r.setEndWords(endWords);
        r.setHangupTriggered(true);
        r.setElapsedSeconds(decision.getElapsedSeconds());
        r.setInvalidChatRounds(decision.getInvalidChatRounds());
        r.setModel(displayModel(modelCfg));
        r.setFromOpening(false);
        r.setLatencyMs(System.currentTimeMillis() - start);
        return r;
    }

    private String displayModel(AiModelConfig cfg) {
        return cfg.getProvider() + ":" + cfg.getModelName();
    }

    private static boolean shouldDeferForcedHangup(String userText) {
        return fallbackForUserUtterance(userText) != null;
    }

    private static String fallbackForUserUtterance(String userText) {
        if (ForcedHangupRules.isIdentityInquiry(userText)) {
            return ForcedHangupRules.identityFallbackReply();
        }
        if (ForcedHangupRules.isServiceInquiry(userText)) {
            return ForcedHangupRules.serviceFallbackReply();
        }
        if (ForcedHangupRules.isHearingIssue(userText)) {
            return ForcedHangupRules.hearingFallbackReply();
        }
        if (ForcedHangupRules.isClarificationQuestion(userText)) {
            return ForcedHangupRules.clarificationFallbackReply();
        }
        return null;
    }

    /** 身份/听不清等优先；槽位跟踪；信息已齐则收尾 */
    private static String quickReplyForUser(AiChatRequest req) {
        String fallback = fallbackForUserUtterance(req.getUserText());
        if (fallback != null) {
            return fallback;
        }
        DialogSlotHelper.Slots slots = DialogSlotHelper.extract(req.getHistory(), req.getUserText());
        if (DialogSlotHelper.shouldEndCall(req.getUserText(), slots, req.getHistory())) {
            return DialogSlotHelper.goodbyeReply(req.getHistory());
        }
        String slotReply = DialogSlotHelper.nextReply(slots, req.getUserText(), req.getHistory());
        if (slotReply != null) {
            return slotReply;
        }
        return null;
    }

    /**
     * 避免只复述客户问句（如客户问「50万还是80万」却只回「50万还是80万？」）。
     */
    private String ensureSubstantiveReply(AiChatRequest req, String reply) {
        if (!StringUtils.hasText(req.getUserText())) {
            return reply;
        }
        String user = req.getUserText().trim();
        String r = reply != null ? reply.trim() : "";
        DialogSlotHelper.Slots slots = DialogSlotHelper.extract(req.getHistory(), req.getUserText());

        if (ForcedHangupRules.isGenericProductIntro(r)
                && (slots.hasAmount || slots.hasTime || slots.hasPurpose)) {
            String alt = DialogSlotHelper.nextReply(slots, user, req.getHistory());
            if (StringUtils.hasText(alt)) {
                return alt;
            }
            return DialogSlotHelper.humanize(ForcedHangupRules.continueDialogReply(user));
        }

        if (user.contains("说过了") || user.contains("讲过") || user.contains("刚说")) {
            String alt = DialogSlotHelper.nextReply(slots, user, req.getHistory());
            if (StringUtils.hasText(alt)) {
                return alt;
            }
        }

        if (!isEchoOnlyReply(user, r, slots)) {
            return r;
        }

        if (ForcedHangupRules.shouldSkipSlotOverride(user)) {
            return r;
        }

        String slotReply = DialogSlotHelper.nextReply(slots, user, req.getHistory());
        if (StringUtils.hasText(slotReply)) {
            return slotReply;
        }
        if (user.contains("多少钱") || user.contains("利率") || user.contains("怎么收")
                || user.contains("费用")) {
            return "我们这边信用贷额度一般二十万到一百万，具体看您资质和用途；您大概想贷多少万呢？";
        }
        if (user.contains("还是") && (user.contains("万") || user.matches(".*\\d+.*"))) {
            return "额度区间大概二十万到一百万，要看征信收入；您是想做五十万还是八十万呢？";
        }
        if (!slots.hasAmount && !slots.hasTime) {
            return ForcedHangupRules.serviceFallbackReply();
        }
        return DialogSlotHelper.humanize(ForcedHangupRules.continueDialogReply(user));
    }

    private static boolean isEchoOnlyReply(String user, String reply, DialogSlotHelper.Slots slots) {
        if (!StringUtils.hasText(reply)) {
            return true;
        }
        if (ForcedHangupRules.isGenericProductIntro(reply)) {
            return slots == null || (!slots.hasAmount && !slots.hasTime && !slots.hasPurpose);
        }
        if (slots != null && slots.hasAmount
                && (user.contains("月") || user.contains("消费") || user.contains("信用贷"))) {
            return false;
        }
        if (user.contains("说过了") || user.contains("讲过") || user.contains("刚说")) {
            return false;
        }
        String u = user.replaceAll("[\\s，,。.!！?？~～]+", "");
        String r = reply.replaceAll("[\\s，,。.!！?？~～]+", "");
        if (r.length() <= 12) {
            return !(slots != null && (slots.hasAmount || slots.hasTime));
        }
        if (u.contains(r) && r.length() >= u.length() * 0.45) {
            return true;
        }
        boolean hasAnswerCue = reply.contains("我们") || reply.contains("额度") || reply.contains("利率")
                || reply.contains("大概") || reply.contains("一般") || reply.contains("可以")
                || reply.contains("看您") || reply.contains("资质") || reply.contains("记下了")
                || reply.contains("明白");
        if (!hasAnswerCue && (reply.contains("还是") || reply.matches(".*\\d+\\s*万.*"))) {
            return slots == null || !slots.hasAmount;
        }
        return false;
    }

    private String polishReply(AiChatRequest req, String reply) {
        String user = req.getUserText() != null ? req.getUserText().trim() : "";
        DialogSlotHelper.Slots slots = DialogSlotHelper.extract(req.getHistory(), req.getUserText());

        if (ForcedHangupRules.isUserFarewell(user)) {
            return DialogSlotHelper.goodbyeReply(req.getHistory());
        }
        if (user.contains("说过了") || user.contains("讲过") || user.contains("刚说")) {
            String ack = DialogSlotHelper.nextReply(slots, user, req.getHistory());
            if (StringUtils.hasText(ack)) {
                return ack;
            }
        }

        String polished = StringUtils.hasText(reply) ? reply : "";
        if (aiVoiceProperties.isDialogLlmPrimary()) {
            polished = DialogSlotHelper.correctUnclearReplyWhenAmountKnown(
                    polished, slots, user, req.getHistory());
        } else {
            String fallback = fallbackForUserUtterance(user);
            if (fallback != null) {
                polished = fallback;
            }
            if (DialogSlotHelper.shouldEndCall(user, slots, req.getHistory())) {
                return DialogSlotHelper.goodbyeReply(req.getHistory());
            }
        }

        String diversified = DialogSlotHelper.diversifyIfNeeded(polished, slots, user, req.getHistory());
        if (StringUtils.hasText(diversified)) {
            return trimFragmentedReply(diversified);
        }
        if (ForcedHangupRules.shouldSkipSlotOverride(user)) {
            return trimFragmentedReply(polished);
        }
        String alt = DialogSlotHelper.nextReply(slots, user, req.getHistory());
        return StringUtils.hasText(alt) ? trimFragmentedReply(alt) : trimFragmentedReply(polished);
    }

    /** 避免 AI 一次说多个问题或过长碎句；AI 实时优先只保留第一句 */
    private String trimFragmentedReply(String reply) {
        if (!StringUtils.hasText(reply)) {
            return reply;
        }
        String t = OralScriptNormalizer.normalize(reply.trim());
        int max = aiVoiceProperties.getMaxSpeakChars();
        if (aiVoiceProperties.isDialogLlmPrimary()) {
            int firstEnd = firstSentenceEndIndex(t);
            if (firstEnd >= 0 && firstEnd < t.length() - 1) {
                t = t.substring(0, firstEnd + 1).trim();
            }
        }
        int firstQ = indexOfQuestionMark(t);
        if (firstQ >= 0 && firstQ < t.length() - 1) {
            int secondQ = indexOfQuestionMark(t.substring(firstQ + 1));
            if (secondQ >= 0 && t.length() > max) {
                return SpeakTextLimiter.limit(t.substring(0, firstQ + 1).trim(), max);
            }
        }
        return SpeakTextLimiter.limit(t, max);
    }

    private static int firstSentenceEndIndex(String text) {
        int end = -1;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '。' || c == '！' || c == '？' || c == '；' || c == '.' || c == '!' || c == '?') {
                end = i;
                break;
            }
        }
        return end;
    }

    private static int indexOfQuestionMark(String text) {
        int cn = text.indexOf('？');
        int en = text.indexOf('?');
        if (cn < 0) {
            return en;
        }
        if (en < 0) {
            return cn;
        }
        return Math.min(cn, en);
    }

    /** 外呼 LLM/TTS 失败时的槽位兜底（不再次调大模型） */
    public AiChatResponse buildLiveCallSlotResponse(AiChatRequest req) {
        AiModelConfig modelCfg = aiModelConfigService.requireActive();
        return buildQuickReply(req, resolveLiveCallFallbackText(req), modelCfg, System.currentTimeMillis());
    }

    private String resolveLiveCallFallbackText(AiChatRequest req) {
        String quick = quickReplyForUser(req);
        if (StringUtils.hasText(quick)) {
            return quick;
        }
        DialogSlotHelper.Slots slots = DialogSlotHelper.extract(req.getHistory(), req.getUserText());
        String slot = DialogSlotHelper.nextReply(slots, req.getUserText(), req.getHistory());
        if (StringUtils.hasText(slot)) {
            return slot;
        }
        return ForcedHangupRules.llmTimeoutFallbackReply();
    }

    private static boolean shouldBlockVectorRagDirect(String userText) {
        if (!StringUtils.hasText(userText)) {
            return false;
        }
        String u = userText.trim();
        return ForcedHangupRules.isIdentityInquiry(u)
                || ForcedHangupRules.isCompanyOrAddressInquiry(u)
                || ForcedHangupRules.isServiceInquiry(u);
    }

    private static boolean preferInstantSlotReply(String userText) {
        if (!StringUtils.hasText(userText)) {
            return false;
        }
        String u = userText.trim();
        if (ForcedHangupRules.isIdentityInquiry(u) || ForcedHangupRules.isServiceInquiry(u)) {
            return false;
        }
        // 有业务含义的短句交给 LLM，避免固定套话
        if (ForcedHangupRules.hasBusinessIntent(u) || ForcedHangupRules.isCooperativeAnswer(u)) {
            return false;
        }
        if (DialogSlotHelper.isAffirmativeNeed(u)) {
            return false;
        }
        String n = u.replaceAll("[\\s，,。.!！?？~～]+", "");
        if (u.contains("怎么") || u.contains("为什么") || u.contains("利率") || u.contains("多少")) {
            return false;
        }
        // 仅纯语气词走槽位快答；与对话训练一致，不把短句默认当槽位
        if (DialogSlotHelper.isFillerOnly(u)) {
            return true;
        }
        return false;
    }

    /**
     * RAG 直出：与对话训练一致保留标准答；外呼仅关键词直出，向量命中改注入 LLM 避免误匹配。
     */
    private AiChatResponse tryBuildRagDirectReply(AiChatRequest req, DialogRagRetrieveResult rag, int kbId,
                                                  AiModelConfig modelCfg, long start) {
        if (rag == null || !rag.isDirectAnswer() || !StringUtils.hasText(rag.getDirectAnswerText())) {
            return null;
        }
        if (req.getCallRecordId() != null && dialogMainFlowService.isEnabled(kbId)) {
            String step = dialogMainFlowService.currentStep(req.getCallRecordId());
            String lastAi = lastAssistantText(req.getHistory());
            if (DialogTrainingIntentRouter.shouldDeferKeywordFaqToMainFlow(req.getUserText(), lastAi, step)) {
                return null;
            }
        }
        if (DialogSlotHelper.shouldBypassRag(req.getUserText())) {
            return null;
        }
        if (!rag.isKeywordMatched() && shouldBlockVectorRagDirect(req.getUserText())) {
            log.info("[RAG] 向量直出降级(身份/地址/服务类) recordId={} kb={} ms={}",
                    req.getCallRecordId(), kbId, rag.getRetrieveMs());
            return null;
        }
        if (req.getCallRecordId() != null && !rag.isKeywordMatched()) {
            log.info("[RAG] 外呼向量直出降级为LLM注入 recordId={} kb={} ms={}",
                    req.getCallRecordId(), kbId, rag.getRetrieveMs());
            return null;
        }
        log.info("[RAG] 高置信直出 recordId={} kb={} keyword={} ms={}",
                req.getCallRecordId(), kbId, rag.isKeywordMatched(), rag.getRetrieveMs());
        String reply = trimReply(rag.getDirectAnswerText());
        reply = trimReply(appendMainFlowResume(req, reply, rag.isKeywordMatched(), kbId));
        return buildQuickReply(req, reply, modelCfg, start, true);
    }

    private AiChatResponse buildFarewellResponse(AiChatRequest req, AiModelConfig modelCfg, long start) {
        String goodbye = trimReply(DialogSlotHelper.goodbyeReply(req.getHistory()));
        if (req.getCallRecordId() != null || StringUtils.hasText(req.getTrainSessionId())) {
            forcedHangupService.afterAiReply(req.getCallRecordId(), req.getTrainSessionId(), goodbye);
        }
        AiChatResponse r = new AiChatResponse();
        r.setReply(goodbye);
        r.setShouldHangup(true);
        r.setEndWords(goodbye);
        r.setHangupTriggered(true);
        r.setModel(displayModel(modelCfg));
        r.setFromOpening(false);
        r.setLatencyMs(System.currentTimeMillis() - start);
        fillHangupMeta(r, req, HangupDecision.none(0, 0));
        return r;
    }

    private static String lastAssistantText(List<AiChatMessage> history) {
        if (history == null) {
            return "";
        }
        for (int i = history.size() - 1; i >= 0; i--) {
            AiChatMessage m = history.get(i);
            if (m != null && "assistant".equalsIgnoreCase(m.getRole()) && StringUtils.hasText(m.getContent())) {
                return m.getContent();
            }
        }
        return "";
    }

    private AiChatResponse buildQuickReply(AiChatRequest req, String reply, AiModelConfig modelCfg, long start) {
        return buildQuickReply(req, reply, modelCfg, start, false);
    }

    private AiChatResponse buildQuickReply(AiChatRequest req, String reply, AiModelConfig modelCfg, long start,
                                           boolean keywordFallback) {
        HangupDecision decision = HangupDecision.none(0, 0);
        if (req.getCallRecordId() != null || StringUtils.hasText(req.getTrainSessionId())) {
            decision = forcedHangupService.evaluateBeforeAi(
                    req.getCallRecordId(), req.getTrainSessionId(),
                    req.getUserText(), req.getBusinessProbeThisTurn());
            if (decision.isShouldHangup()) {
                if (HangupType.FORCE_REFUSE.equals(decision.getHangupType())
                        && (ForcedHangupRules.hasBusinessIntent(req.getUserText())
                        || ForcedHangupRules.isCooperativeAnswer(req.getUserText()))) {
                    decision = HangupDecision.none(decision.getElapsedSeconds(), 0);
                } else {
                    return buildHangupResponse(decision, start, modelCfg);
                }
            }
            forcedHangupService.afterAiReply(req.getCallRecordId(), req.getTrainSessionId(), reply);
            decision = HangupDecision.none(decision.getElapsedSeconds(), decision.getInvalidChatRounds());
        }
        DialogSlotHelper.Slots slots = DialogSlotHelper.extract(req.getHistory(), req.getUserText());
        boolean endCall = DialogSlotHelper.shouldEndCall(req.getUserText(), slots, req.getHistory())
                || isMainFlowHangupReply(req, reply);
        if (DialogSlotHelper.shouldBypassRag(req.getUserText())) {
            endCall = false;
        }
        String polished = keywordFallback ? reply : DialogSlotHelper.diversifyIfNeeded(reply, slots, req.getUserText(), req.getHistory());
        String body = polished != null ? polished : reply;
        String finalReply = endCall ? DialogSlotHelper.goodbyeReply(req.getHistory()) : trimReply(body);

        AiChatResponse r = new AiChatResponse();
        r.setReply(finalReply);
        r.setShouldHangup(endCall);
        if (endCall) {
            r.setEndWords(finalReply);
            r.setHangupTriggered(true);
        }
        r.setBusinessProbeNext(ForcedHangupRules.aiReplyIsBusinessProbe(reply));
        r.setModel(displayModel(modelCfg));
        r.setFromOpening(false);
        r.setLatencyMs(System.currentTimeMillis() - start);
        fillHangupMeta(r, req, decision);
        return r;
    }

    private String resolveOpeningReply(AiChatRequest req, DialogCallContext ctx, int kbId, AiPrompt prompt) {
        if (req.getCallRecordId() != null && dialogMainFlowService.isEnabled(kbId)) {
            String script = dialogMainFlowService.openingScript(kbId);
            if (StringUtils.hasText(script)) {
                return trimReply(script);
            }
        }
        if (prompt != null && StringUtils.hasText(prompt.getOpeningRemarks())) {
            return trimReply(prompt.getOpeningRemarks());
        }
        return trimReply(activePrompt().getOpeningRemarks());
    }

    private String appendMainFlowResume(AiChatRequest req, String fallbackAnswer, boolean keywordMatched, int kbId) {
        if (!keywordMatched || req.getCallRecordId() == null || !dialogMainFlowService.isEnabled(kbId)) {
            return fallbackAnswer;
        }
        if (DialogSlotHelper.isExplicitCustomerQuestion(req.getUserText())
                || DialogSlotHelper.isFollowUpComplaint(req.getUserText())) {
            return fallbackAnswer;
        }
        if (fallbackAnswer.contains("再见") && fallbackAnswer.length() > 12) {
            return fallbackAnswer;
        }
        String resume = dialogMainFlowService.resumeAfterFallback(req.getCallRecordId());
        if (!StringUtils.hasText(resume)) {
            return fallbackAnswer;
        }
        return fallbackAnswer + " " + resume;
    }

    private boolean isMainFlowHangupReply(AiChatRequest req, String reply) {
        if (req.getCallRecordId() == null || !StringUtils.hasText(reply)) {
            return false;
        }
        DialogCallContext ctx = req.getCallRecordId() != null
                ? callContextCacheService.get(req.getCallRecordId())
                : dialogCallContextService.resolve(req.getCallRecordId());
        int kbId = ctx.hasKb() ? ctx.getKbId() : DialogCallContextService.DEFAULT_KB_ID;
        if (!dialogMainFlowService.isEnabled(kbId)) {
            return false;
        }
        String step = dialogMainFlowService.currentStep(req.getCallRecordId());
        return ("19".equals(step) || "20".equals(step) || "21".equals(step) || "24".equals(step)
                || "25".equals(step) || "26".equals(step) || "99".equals(step))
                && reply.contains("再见");
    }

    private void fillHangupMeta(AiChatResponse r, AiChatRequest req, HangupDecision decision) {
        if (req.getCallRecordId() == null && !StringUtils.hasText(req.getTrainSessionId())) {
            return;
        }
        r.setElapsedSeconds(decision.getElapsedSeconds());
        r.setInvalidChatRounds(decision.getInvalidChatRounds());
        if (r.getShouldHangup() == null) {
            r.setShouldHangup(false);
        }
    }

    public AiCallSummaryResponse summarize(AiCallSummaryRequest req) {
        if (!StringUtils.hasText(req.getDialogText())) {
            throw new BizException("dialogText 不能为空");
        }
        AiModelConfig modelCfg = aiModelConfigService.requireActive();
        AiPrompt prompt = activePrompt();
        String system = prompt.getPromptContent()
                + "\n\n请根据以下完整对话，严格只输出一行，字段用|分隔，不要其它说明文字：\n"
                + "客户需求|客户痛点|预算|最佳回访时间|意向等级\n"
                + "意向等级仅填 A/B/C/D 其中一个。\n"
                + "分级标准：A=明确表示需要贷款/资金且说了额度或用途；B=有资金需求、在了解或考虑中；"
                + "C=未明确需求但未拒绝；D=明确拒绝或完全无意向。\n"
                + "客户说过「需要、要贷、贷款、周转、多少万」等，禁止判为 D。";
        List<Map<String, String>> messages = List.of(
                Map.of("role", "system", "content", system),
                Map.of("role", "user", "content", req.getDialogText())
        );
        String raw = llmInvokeService.chat(messages, modelCfg);
        AiCallSummaryResponse parsed = parseSummary(raw);
        String ruleLevel = intentLevelService.inferFromDialog(req.getDialogText(), 0);
        parsed.setLevel(intentLevelService.mergeLevel(parsed.getLevel(), ruleLevel));
        return parsed;
    }

    private String buildSystemPrompt(AiPrompt prompt, int elapsedSeconds, List<AiChatMessage> history,
                                     String currentUser, DialogRagRetrieveResult rag, Integer callRecordId) {
        StringBuilder sb = new StringBuilder(prompt.getPromptContent());
        if (callRecordId != null) {
            sb.append("\n\n【本通电话】recordId=").append(callRecordId)
                    .append("；上下文仅来自本通外呼对话，不同客户/不同通话互不共享，禁止引用其他通话信息。");
        }
        if (dialogRagProperties.isEnabled() && rag != null && rag.isHasPositiveMatch()) {
            sb.append(dialogRagRetrievalService.buildRagPromptBlock(rag));
        } else if (dialogRagProperties.isEnabled()) {
            sb.append("\n\n【知识库参考】优先参考人工训练标准问答；无匹配时可结合本通电话上下文自然作答，")
                    .append("禁止编造具体利率/承诺放款；单次不超过")
                    .append(aiVoiceProperties.getMaxSpeakChars()).append("字，口语化。");
        }
        DialogSlotHelper.Slots slots = DialogSlotHelper.extract(history, currentUser);
        sb.append(DialogSlotHelper.promptSummary(slots));
        sb.append("\n\n【说话风格】像资深金融信贷顾问打电话：亲切稳重、自然流畅，不要背稿；")
                .append("拒绝长句和书面语，改成口语短句；")
                .append("【重要】每次只说一句话，不超过")
                .append(aiVoiceProperties.getMaxSpeakChars())
                .append("字；说完就停，等客户接话；")
                .append("核心卖点单独成句，不要一次堆多个问题；")
                .append("禁止「诸如、综上所述、也就是说」；一次只问一个问题；")
                .append("可用「嗯」「好的」「没事」「不好意思啊」；禁止机械套话、禁止连续两个问号；")
                .append("客户听不清或回答含糊时，换种说法再问，不要复制上一轮原句。");
        sb.append("\n【实时对话】严格轮次：你只在客户说完并停顿后才回复；")
                .append("每轮仅1句口语，必须说完整，总长不超过")
                .append(aiVoiceProperties.getMaxSpeakChars()).append("字；已通话")
                .append(elapsedSeconds).append("秒（上限")
                .append(ForcedHangupRules.MAX_CALL_SECONDS).append("秒）。")
                .append("必须结合下方对话历史与槽位回答，禁止脱离本通电话上下文。")
                .append("【当前优先】必须先直接回应客户本句「").append(currentUser.trim())
                .append("」，禁止无视提问继续按推销主线往下问。")
                .append("客户问利率/利息/额度/办理方式/公司身份时，先给简明答案再酌情追问。")
                .append("客户问「多少钱/利率/要多少」时：必须先说明额度或费用区间再给建议，禁止只重复客户说的数字。")
                .append("客户问「50万还是80万」时：先答额度一般二十万到一百万、看资质，再问清他要五十万还是八十万。");
        if (history != null && !history.isEmpty()) {
            sb.append("开场白已播过，不要重复问「有没有资金需求」；针对客户上一句回应。");
        }
        sb.append("客户已说清的信息不要再问；需要澄清时语气柔和。");
        sb.append("客户说纯数字或「八十」「五十」等时，在问额度场景下理解为「八十万」「五十万」，禁止再说没听清。");
        if (aiVoiceProperties.isDialogLlmPrimary() && history != null && !history.isEmpty()) {
            sb.append("\n\n【上下文提示】下方消息列表已含完整对话，请结合客户最新一句回复，勿重复已问过的问题。");
        } else if (history != null && !history.isEmpty()) {
            sb.append("\n\n【完整对话记录（请结合上下文回复，勿重复已问过的问题）】\n");
            appendTranscript(sb, history);
            sb.append("客户：").append(currentUser.trim());
        }
        sb.append("额度、时间、用途都收集齐后，收尾只说一次，不要每轮重复「记下了、还有其他想了解吗」；")
                .append("客户说没有了就礼貌告别并结束；客户问哪里/谁/什么公司必须直接回答身份。");
        sb.append("客户问「你是哪里/什么公司/哪位」时，用一两句说明身份与来电目的，禁止照搬开场白全文。")
                .append("客户问提供哪些服务/产品时，简要说明贷款或周转类产品并反问金额用途。")
                .append("客户已回答金额、用途、时间、个人/经营等具体问题后，必须继续追问下一项，不要主动结束通话。")
                .append("客户说「听不清/听不见」时，放慢语速、用更短句子重复要点。")
                .append("客户问「什么意思/新需求吗」时，用一句话解释来电目的，再继续询问需求。")
                .append("话术里禁止出现「XX公司」等占位符，用真实机构表述。")
                .append("禁止在回复末尾输出 [挂断触发]；是否结束通话由系统根据辱骂投诉、客户明确拒接、满5分钟三类规则判定，你只需正常对话。")
                .append("参考结束语（仅系统挂断时播放，你日常回复不要用）：")
                .append(StringUtils.hasText(prompt.getEndRemarks())
                        ? prompt.getEndRemarks() : ForcedHangupRules.END_WORDS);
        return sb.toString();
    }

    public Map<String, Object> hangupRulesInfo() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("maxCallSeconds", ForcedHangupRules.MAX_CALL_SECONDS);
        m.put("rules", List.of(
                "辱骂、脏话、投诉举报 → 礼貌结束",
                "客户明确表示不要再打扰 → 礼貌结束",
                "通话满5分钟 → 说明情况后礼貌结束"));
        m.put("endWords", ForcedHangupRules.END_WORDS);
        m.put("durationEndWords", ForcedHangupRules.DURATION_END_WORDS);
        m.put("hangupTypes", List.of(
                HangupType.NORMAL, HangupType.FORCE_ABUSE, HangupType.FORCE_REFUSE,
                HangupType.FORCE_DURATION));
        return m;
    }

    private void appendTranscript(StringBuilder sb, List<AiChatMessage> history) {
        for (AiChatMessage m : history) {
            if (m == null || !StringUtils.hasText(m.getContent())) {
                continue;
            }
            String role = "assistant".equalsIgnoreCase(m.getRole()) ? "客服" : "客户";
            sb.append(role).append("：").append(m.getContent().trim()).append('\n');
        }
    }

    private int effectiveHistoryRounds(AiModelConfig cfg) {
        int fromModel = cfg.getMaxHistoryRounds() != null ? cfg.getMaxHistoryRounds() : 3;
        int cap = aiVoiceProperties.getDialogMaxHistoryRounds();
        return cap > 0 ? Math.min(fromModel, cap) : fromModel;
    }

    private AiModelConfig cappedModelCfg(AiModelConfig cfg) {
        int cap = aiVoiceProperties.getDialogLlmMaxTokens();
        Double tempOverride = aiVoiceProperties.getDialogLlmTemperature();
        Double topPOverride = aiVoiceProperties.getDialogLlmTopP();
        boolean needCopy = (cap > 0 && cfg.getMaxTokens() != null && cfg.getMaxTokens() > cap)
                || tempOverride != null
                || (topPOverride != null && topPOverride > 0 && topPOverride < 1.0);
        if (!needCopy) {
            return cfg;
        }
        AiModelConfig c = new AiModelConfig();
        org.springframework.beans.BeanUtils.copyProperties(cfg, c);
        if (cap > 0 && cfg.getMaxTokens() != null && cfg.getMaxTokens() > cap) {
            c.setMaxTokens(cap);
        }
        if (tempOverride != null) {
            c.setTemperature(java.math.BigDecimal.valueOf(tempOverride));
        }
        if (topPOverride != null && topPOverride > 0 && topPOverride <= 1.0) {
            c.setTopP(topPOverride);
        }
        return c;
    }

    private void appendHistory(List<Map<String, String>> messages, List<AiChatMessage> history, AiModelConfig cfg) {
        if (history == null || history.isEmpty()) {
            return;
        }
        int maxMessages = effectiveHistoryRounds(cfg) * 2;
        int from = Math.max(0, history.size() - maxMessages);
        for (int i = from; i < history.size(); i++) {
            AiChatMessage m = history.get(i);
            if (!StringUtils.hasText(m.getRole()) || !StringUtils.hasText(m.getContent())) {
                continue;
            }
            String role = "assistant".equalsIgnoreCase(m.getRole()) ? "assistant" : "user";
            messages.add(Map.of("role", role, "content", m.getContent().trim()));
        }
    }

    private AiCallSummaryResponse parseSummary(String raw) {
        AiCallSummaryResponse r = new AiCallSummaryResponse();
        r.setLevel("D");
        if (!StringUtils.hasText(raw)) {
            return r;
        }
        String pipeLine = raw.lines()
                .map(String::trim)
                .filter(line -> line.contains("|"))
                .reduce((a, b) -> b)
                .orElse(raw.trim());
        if (pipeLine.contains("|")) {
            String[] parts = pipeLine.split("\\|", -1);
            if (parts.length >= 5) {
                r.setCustomerNeed(parts[0].trim());
                r.setCustomerPain(parts[1].trim());
                r.setBudget(parts[2].trim());
                r.setNextTime(parts[3].trim());
                r.setLevel(normalizeLevel(parts[4]));
                return r;
            }
        }
        try {
            int start = raw.indexOf('{');
            int end = raw.lastIndexOf('}');
            if (start >= 0 && end > start) {
                JsonNode j = objectMapper.readTree(raw.substring(start, end + 1));
                r.setLevel(normalizeLevel(textOr(j, "level", "D")));
                r.setCustomerNeed(textOr(j, "customerNeed", ""));
                r.setCustomerPain(textOr(j, "customerPain", ""));
                r.setBudget(textOr(j, "budget", ""));
                r.setNextTime(textOr(j, "nextTime", ""));
            }
        } catch (Exception e) {
            log.warn("解析意向结果失败: {}", raw);
        }
        return r;
    }

    private String normalizeLevel(String levelRaw) {
        if (!StringUtils.hasText(levelRaw)) {
            return "D";
        }
        String s = levelRaw.trim().toUpperCase();
        if (s.length() == 1 && "ABCD".contains(s)) {
            return s;
        }
        for (char c : new char[]{'A', 'B', 'C', 'D'}) {
            if (s.indexOf(c) >= 0) {
                return String.valueOf(c);
            }
        }
        return "D";
    }

    private String textOr(JsonNode j, String field, String def) {
        return j.has(field) && !j.get(field).isNull() ? j.get(field).asText() : def;
    }

    private String trimReply(String reply) {
        if (reply == null) {
            return "";
        }
        return SpeakTextLimiter.limit(reply, aiVoiceProperties.getMaxSpeakChars());
    }
}
