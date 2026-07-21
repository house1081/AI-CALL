package com.aicall.service;

import com.aicall.common.BizException;
import com.aicall.common.DialogSlotHelper;
import com.aicall.common.DialogTrainingIntentRouter;
import com.aicall.common.ForcedHangupRules;
import com.aicall.common.HangupType;
import com.aicall.common.MainFlowContextBridge;
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
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
@RequiredArgsConstructor
public class OllamaChatService {

    private static final java.util.concurrent.ScheduledExecutorService TTFT_WATCH =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "llm-ttft-watch");
                t.setDaemon(true);
                return t;
            });

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
     * @param onFirstChunk 流式首句回调；返回 true 表示已真正开播（供 TTFT 门控）
     */
    public AiChatResponse chat(AiChatRequest req, java.util.function.Predicate<String> onFirstChunk) {
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
        if (ForcedHangupRules.isHardNoDisturbance(req.getUserText(), lastAi)) {
            log.info("[对话LLM] 客户强硬勿扰，礼貌挂机 recordId={} user={}",
                    req.getCallRecordId(),
                    req.getUserText().length() > 24 ? req.getUserText().substring(0, 24) + "..." : req.getUserText());
            return buildFarewellResponse(req, modelCfg, start);
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

        // AI 外呼：每轮 LLM + 完整上下文；主线只作引导，禁止硬播短路
        if (aiVoiceProperties.isDialogLlmPrimary() && req.getCallRecordId() != null) {
            return chatAiOutboundContextual(req, onFirstChunk, modelCfg, prompt, kbId, start, lastAi, pre);
        }

        // 以下为训练 / 非 LLM-primary 兼容路径
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
        }

        if (req.getCallRecordId() != null && DialogSlotHelper.shouldBypassRag(req.getUserText())) {
            dialogMainFlowService.ensureInit(req.getCallRecordId(), kbId);
            if (dialogMainFlowService.isEnabled(kbId)) {
                String mainLine = dialogMainFlowService.nextMainLineAfterUser(
                        req.getCallRecordId(), req.getUserText());
                if (StringUtils.hasText(mainLine)) {
                    return buildMainFlowTurnReply(req, mainLine, modelCfg, start);
                }
            }
            return buildQuickReply(req, trimReply(ForcedHangupRules.hearingFallbackReply()),
                    modelCfg, start);
        }

        if (dialogRagProperties.isEnabled()
                && ForcedHangupRules.isCompanyOrAddressInquiry(req.getUserText())) {
            DialogRagRetrieveResult keywordHit = dialogRagRetrievalService.tryKeywordDirectAnswer(
                    req.getUserText(), kbId);
            if (keywordHit.isDirectAnswer() && StringUtils.hasText(keywordHit.getDirectAnswerText())) {
                return buildQuickReply(req, trimReply(keywordHit.getDirectAnswerText()),
                        modelCfg, start, true);
            }
        }

        String intentQuick = fallbackForUserUtterance(req.getUserText());
        if (StringUtils.hasText(intentQuick)) {
            return buildQuickReply(req, trimReply(intentQuick), modelCfg, start, true);
        }

        DialogRagRetrieveResult rag = null;
        if (dialogRagProperties.isEnabled()) {
            rag = dialogRagRetrievalService.retrieve(req.getUserText(), kbId);
            AiChatResponse ragReply = tryBuildRagDirectReply(req, rag, kbId, modelCfg, start);
            if (ragReply != null) {
                return ragReply;
            }
            if (rag.isNoMatchFallback() && !dialogMainFlowService.isEnabled(kbId)) {
                return buildQuickReply(req, trimReply(dialogRagRetrievalService.noMatchFallbackText()),
                        modelCfg, start, false);
            }
        }

        if (req.getCallRecordId() != null && dialogMainFlowService.isEnabled(kbId)
                && !DialogSlotHelper.prefersContextualLlmReply(req.getUserText(),
                rag != null && rag.isHasPositiveMatch())) {
            String mainLine = dialogMainFlowService.nextMainLineAfterUser(
                    req.getCallRecordId(), req.getUserText(), lastAi);
            if (StringUtils.hasText(mainLine)) {
                return buildMainFlowTurnReply(req, mainLine, modelCfg, start);
            }
        }

        return invokeLlmTurn(req, onFirstChunk, modelCfg, prompt, rag, start, pre, null, false);
    }

    /**
     * AI 实时外呼：LLM 为主，主线 peek 引导；应答成功后再 commit 推进。
     */
    private AiChatResponse chatAiOutboundContextual(AiChatRequest req, java.util.function.Predicate<String> onFirstChunk,
                                                    AiModelConfig modelCfg, AiPrompt prompt, int kbId,
                                                    long start, String lastAi, HangupDecision pre) {
        dialogMainFlowService.ensureInit(req.getCallRecordId(), kbId);

        // 含糊：澄清，不推进主线、不乱答
        if (ForcedHangupRules.isVagueOrUnclearTranscript(req.getUserText())
                && !ForcedHangupRules.isCooperativeAnswer(req.getUserText())
                && !ForcedHangupRules.hasBusinessIntent(req.getUserText())
                && !DialogSlotHelper.isStatingLoanAmount(req.getUserText())
                && !ForcedHangupRules.isIdentityInquiry(req.getUserText())
                && !ForcedHangupRules.isHearingIssue(req.getUserText())) {
            String clarify = ForcedHangupRules.unclearClarifyReply(lastAi);
            String current = dialogMainFlowService.resumeAfterFallback(req.getCallRecordId());
            if (StringUtils.hasText(current) && current.length() <= 40) {
                clarify = "不好意思没听清，" + stripLeadingWeakAck(current);
            }
            log.info("[对话LLM] AI外呼含糊澄清 recordId={} user={}", req.getCallRecordId(), req.getUserText());
            return buildQuickReply(req, SpeakTextLimiter.limit(clarify, speakBudget()), modelCfg, start, false);
        }

        // 身份/听不清/嫌慢：极短快答（低延迟），不抢主线推进
        if (ForcedHangupRules.isHearingIssue(req.getUserText())
                || ForcedHangupRules.isIdentityInquiry(req.getUserText())
                || ForcedHangupRules.isServiceInquiry(req.getUserText())
                || ForcedHangupRules.isClarificationQuestion(req.getUserText())
                || ForcedHangupRules.isLatencyComplaint(req.getUserText())) {
            String quick = fallbackForUserUtterance(req.getUserText());
            if (StringUtils.hasText(quick)) {
                String resume = dialogMainFlowService.resumeAfterFallback(req.getCallRecordId());
                String body = quick;
                if (StringUtils.hasText(resume) && !quick.contains("？") && resume.contains("？")
                        && !ForcedHangupRules.isLatencyComplaint(req.getUserText())) {
                    body = SpeakTextLimiter.limit(quick + " " + stripLeadingWeakAck(resume), speakBudget());
                }
                return buildQuickReply(req, body, modelCfg, start, true);
            }
        }

        // FAQ 关键词直出（征信/利率等）：毫秒级开口，禁止再走慢向量嵌入+LLM
        if (dialogRagProperties.isEnabled()) {
            DialogRagRetrieveResult keywordHit = dialogRagRetrievalService.tryKeywordDirectAnswer(
                    req.getUserText(), kbId);
            if (keywordHit.isDirectAnswer() && StringUtils.hasText(keywordHit.getDirectAnswerText())) {
                String ans = OralScriptNormalizer.normalize(keywordHit.getDirectAnswerText().trim());
                String resume = dialogMainFlowService.resumeAfterFallback(req.getCallRecordId());
                if (StringUtils.hasText(resume) && !ans.contains("？") && resume.contains("？")) {
                    ans = SpeakTextLimiter.limit(ans + " " + stripLeadingWeakAck(resume), speakBudget());
                } else {
                    ans = SpeakTextLimiter.limit(ans, speakBudget());
                }
                log.info("[对话LLM] AI外呼关键词直出 recordId={} ruleId={} replyLen={}",
                        req.getCallRecordId(), keywordHit.getKeywordRuleId(),
                        ans != null ? ans.length() : 0);
                return buildQuickReply(req, ans, modelCfg, start, true);
            }
        }

        boolean customerQuestion = DialogSlotHelper.prefersContextualLlmReply(req.getUserText(), false)
                || DialogSlotHelper.isExplicitCustomerQuestion(req.getUserText());

        // 额度/短答/主线推进：槽位快路径，避开 5~11s LLM 空等导致「没说话就挂」
        if (!customerQuestion
                && dialogMainFlowService.isEnabled(kbId)
                && (DialogSlotHelper.isStatingLoanAmount(req.getUserText())
                || DialogSlotHelper.shouldPreferMainFlowAdvance(req.getUserText())
                || ForcedHangupRules.isCooperativeAnswer(req.getUserText()))) {
            AiChatResponse fast = tryOutboundSlotMainFlowFastPath(req, modelCfg, kbId, start, lastAi);
            if (fast != null) {
                return fast;
            }
        }

        String peekGuide = null;
        if (dialogMainFlowService.isEnabled(kbId)) {
            if (customerQuestion) {
                peekGuide = dialogMainFlowService.resumeAfterFallback(req.getCallRecordId());
                log.info("[主线] AI外呼客户提问，peek 当前题引导 recordId={} step={}",
                        req.getCallRecordId(), dialogMainFlowService.currentStep(req.getCallRecordId()));
            } else {
                peekGuide = dialogMainFlowService.peekNextScript(
                        req.getCallRecordId(), req.getUserText(), lastAi);
                if (!StringUtils.hasText(peekGuide)) {
                    peekGuide = dialogMainFlowService.resumeAfterFallback(req.getCallRecordId());
                }
                log.info("[主线] AI外呼 peek 引导 recordId={} step={} guideLen={}",
                        req.getCallRecordId(), dialogMainFlowService.currentStep(req.getCallRecordId()),
                        peekGuide != null ? peekGuide.length() : 0);
            }
        }

        DialogRagRetrieveResult rag = null;
        if (dialogRagProperties.isEnabled()) {
            rag = retrieveOutboundBounded(req.getUserText(), kbId);
            // 外呼：向量命中只注入 LLM；关键词已在上方直出
        }

        AiChatResponse llmResp = invokeLlmTurn(req, onFirstChunk, modelCfg, prompt, rag, start, pre,
                peekGuide, true);
        if (llmResp != null && StringUtils.hasText(llmResp.getReply())
                && !Boolean.TRUE.equals(llmResp.getShouldHangup())
                && dialogMainFlowService.isEnabled(kbId)
                && !customerQuestion
                && !ForcedHangupRules.isAsrCorrectionOrRetraction(req.getUserText())) {
            dialogMainFlowService.commitAdvanceAfterReply(
                    req.getCallRecordId(), req.getUserText(), lastAi);
            log.info("[主线] AI外呼 commit 推进 recordId={} step={}",
                    req.getCallRecordId(), dialogMainFlowService.currentStep(req.getCallRecordId()));
        }
        return llmResp;
    }

    /**
     * 报额度/短应等：直接槽位或主线桥接，毫秒级开口。
     */
    private AiChatResponse tryOutboundSlotMainFlowFastPath(AiChatRequest req, AiModelConfig modelCfg,
                                                           int kbId, long start, String lastAi) {
        DialogSlotHelper.Slots slots = DialogSlotHelper.extract(req.getHistory(), req.getUserText());
        String slotReply = DialogSlotHelper.nextReply(slots, req.getUserText(), req.getHistory());
        String peek = dialogMainFlowService.peekNextScript(
                req.getCallRecordId(), req.getUserText(), lastAi);
        if (!StringUtils.hasText(peek)) {
            peek = dialogMainFlowService.resumeAfterFallback(req.getCallRecordId());
        }
        String body = null;
        if (StringUtils.hasText(slotReply) && slotReply.length() >= 8
                && !isWeakAckOnlyReply(slotReply)) {
            body = slotReply;
        } else if (StringUtils.hasText(peek)) {
            body = MainFlowContextBridge.bridge(req.getUserText(), lastAi, peek);
        }
        if (!StringUtils.hasText(body) || isWeakAckOnlyReply(body)) {
            return null;
        }
        body = SpeakTextLimiter.limit(OralScriptNormalizer.normalize(body.trim()), speakBudget());
        if (!StringUtils.hasText(body) || body.length() < 6) {
            return null;
        }
        dialogMainFlowService.commitAdvanceAfterReply(
                req.getCallRecordId(), req.getUserText(), lastAi);
        log.info("[对话LLM] 槽位/主线快路径 recordId={} user={} replyLen={} step={}",
                req.getCallRecordId(),
                req.getUserText().length() > 20 ? req.getUserText().substring(0, 20) + "…" : req.getUserText(),
                body.length(),
                dialogMainFlowService.currentStep(req.getCallRecordId()));
        return buildQuickReply(req, body, modelCfg, start, false);
    }

    /** 外呼向量检索：硬超时跳过，避免嵌入接口卡死导致客户空等挂机 */
    private DialogRagRetrieveResult retrieveOutboundBounded(String userText, int kbId) {
        int timeoutMs = Math.max(200, Math.min(3000, dialogRagProperties.getRetrieveTimeoutMs()));
        try {
            return java.util.concurrent.CompletableFuture
                    .supplyAsync(() -> dialogRagRetrievalService.retrieve(userText, kbId))
                    .orTimeout(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                    .join();
        } catch (Exception e) {
            Throwable cause = e instanceof java.util.concurrent.CompletionException && e.getCause() != null
                    ? e.getCause() : e;
            log.warn("[RAG] 外呼检索超时/失败，跳过注入 timeoutMs={} reason={}",
                    timeoutMs, cause.getMessage());
            DialogRagRetrieveResult empty = new DialogRagRetrieveResult();
            empty.setRetrieveMs(timeoutMs);
            return empty;
        }
    }

    private AiChatResponse invokeLlmTurn(AiChatRequest req, java.util.function.Predicate<String> onFirstChunk,
                                         AiModelConfig modelCfg, AiPrompt prompt,
                                         DialogRagRetrieveResult rag, long start, HangupDecision pre,
                                         String mainFlowGuide, boolean aiOutbound) {
        String system = buildSystemPrompt(prompt, pre.getElapsedSeconds(), req.getHistory(),
                req.getUserText(), rag, req.getCallRecordId());
        if (StringUtils.hasText(mainFlowGuide)) {
            if (aiOutbound) {
                system = system + "\n\n【主线引导】若客户在回答上一问，先用半句口语接住，再自然问出意思相同的话："
                        + "「" + mainFlowGuide.trim() + "」。"
                        + "可以把书面说法改口语，例如「请问需要多少资金」→「那您大概要多少资金」；"
                        + "若客户在提问，先答提问，再酌情接回。禁止无视客户本句硬背原文；"
                        + "听不清就换说法重问，不要编造利率或放款承诺。";
            } else {
                system = system + "\n\n【主线待办】答完客户本句后，用半句自然衔接到主线问题：「"
                        + mainFlowGuide.trim() + "」。";
            }
        }
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", system));
        appendHistory(messages, req.getHistory(), modelCfg);
        messages.add(Map.of("role", "user", "content", req.getUserText().trim()));

        AiModelConfig llmCfg = cappedModelCfg(modelCfg);
        int histSize = req.getHistory() != null ? req.getHistory().size() : 0;
        boolean stream = aiVoiceProperties.isDialogLlmStream();
        long llmStart = System.currentTimeMillis();
        log.info("[对话LLM] {}调用大模型 recordId={} historyMsgs={} maxTokens={} outbound={} user={}",
                stream ? "流式" : "",
                req.getCallRecordId(), histSize, llmCfg.getMaxTokens(), aiOutbound,
                req.getUserText().length() > 40 ? req.getUserText().substring(0, 40) + "..." : req.getUserText());

        String rawReply;
        java.util.concurrent.ScheduledFuture<?> ttftWatch = null;
        try {
            if (stream) {
                LlmStreamingSentenceBuffer sentenceBuf = new LlmStreamingSentenceBuffer(
                        aiVoiceProperties.getMaxSpeakChars(),
                        aiVoiceProperties.getStreamTtsFirstChunkChars());
                AtomicBoolean firstSentenceEmitted = new AtomicBoolean(false);
                int ttftSec = Math.max(0, Math.min(30, aiVoiceProperties.getDialogLlmTtftTimeoutSec()));
                Thread worker = Thread.currentThread();
                if (ttftSec > 0) {
                    ttftWatch = TTFT_WATCH.schedule(() -> {
                        if (!firstSentenceEmitted.get()) {
                            log.warn("[对话LLM] TTFT门控中断 recordId={} ttftSec={}",
                                    req.getCallRecordId(), ttftSec);
                            worker.interrupt();
                        }
                    }, ttftSec, java.util.concurrent.TimeUnit.SECONDS);
                }
                rawReply = llmInvokeService.chatStreaming(messages, llmCfg, delta -> {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new BizException("大模型首句超时 " + ttftSec + "s");
                    }
                    for (String sentence : sentenceBuf.feed(delta)) {
                        if (onFirstChunk != null && onFirstChunk.test(sentence)) {
                            firstSentenceEmitted.set(true);
                        }
                    }
                    if (ttftSec > 0 && !firstSentenceEmitted.get()
                            && System.currentTimeMillis() - llmStart > ttftSec * 1000L) {
                        String forced = sentenceBuf.flushRemainder();
                        if (StringUtils.hasText(forced) && onFirstChunk != null
                                && onFirstChunk.test(forced)) {
                            firstSentenceEmitted.set(true);
                            log.info("[对话LLM] TTFT超时强制开播 recordId={} len={} ttftSec={}",
                                    req.getCallRecordId(), forced.length(), ttftSec);
                            return;
                        }
                        // 仍无真正开播：立刻走主线/槽位兜底，禁止继续空等
                        throw new BizException("大模型首句超时 " + ttftSec + "s");
                    }
                });
                String tail = sentenceBuf.flushRemainder();
                if (onFirstChunk != null && StringUtils.hasText(tail)) {
                    if (onFirstChunk.test(tail)) {
                        firstSentenceEmitted.set(true);
                    }
                }
            } else {
                rawReply = llmInvokeService.chat(messages, llmCfg);
            }
        } catch (Exception e) {
            log.warn("[对话LLM] 调用失败 recordId={}: {}", req.getCallRecordId(), e.getMessage());
            if (req.getCallRecordId() != null && aiOutbound && StringUtils.hasText(mainFlowGuide)) {
                String bridged = MainFlowContextBridge.bridge(
                        req.getUserText(), lastAssistantText(req.getHistory()), mainFlowGuide);
                dialogMainFlowService.commitAdvanceAfterReply(
                        req.getCallRecordId(), req.getUserText(), lastAssistantText(req.getHistory()));
                return buildQuickReply(req, SpeakTextLimiter.limit(bridged, speakBudget()),
                        modelCfg, start, false);
            }
            if (req.getCallRecordId() != null) {
                return buildQuickReply(req, resolveLiveCallFallbackText(req), modelCfg, start);
            }
            if (e instanceof BizException be) {
                throw be;
            }
            throw new BizException("大模型调用失败: " + e.getMessage());
        } finally {
            if (ttftWatch != null) {
                ttftWatch.cancel(false);
            }
            // 清除门控留下的中断标记，避免污染后续 TTS
            //noinspection ResultOfMethodCallIgnored
            Thread.interrupted();
        }
        log.info("[对话LLM] 完成 recordId={} 耗时{}ms 字数={}",
                req.getCallRecordId(), System.currentTimeMillis() - llmStart,
                rawReply != null ? rawReply.length() : 0);
        String cleaned = forcedHangupService.stripHangupMarkers(rawReply);
        // 流式 TTS 已边生成边播：禁止 diversify/槽位改写整句，否则与已播前缀错位触发整段重播
        if (onFirstChunk != null && aiOutbound) {
            cleaned = polishReplyPreserveStream(req, cleaned);
        } else {
            cleaned = polishReply(req, cleaned);
        }
        cleaned = ensureSubstantiveReply(req, cleaned);
        if (req.getCallRecordId() != null || StringUtils.hasText(req.getTrainSessionId())) {
            forcedHangupService.afterAiReply(req.getCallRecordId(), req.getTrainSessionId(), cleaned);
        }

        DialogSlotHelper.Slots slots = DialogSlotHelper.extract(req.getHistory(), req.getUserText());
        boolean slotsSayEnd = DialogSlotHelper.shouldEndCall(req.getUserText(), slots, req.getHistory());
        // AI 外呼：槽位齐了也不自动挂机，除非客户明确告别（上层已处理）
        boolean endCall = !aiOutbound && !aiVoiceProperties.isDialogLlmPrimary() && slotsSayEnd;
        int budget = speakBudget();
        String finalReply = StringUtils.hasText(cleaned) ? SpeakTextLimiter.limit(cleaned, budget) : "";
        if (!StringUtils.hasText(finalReply) && req.getCallRecordId() != null) {
            if (StringUtils.hasText(mainFlowGuide)) {
                finalReply = SpeakTextLimiter.limit(
                        MainFlowContextBridge.bridge(req.getUserText(),
                                lastAssistantText(req.getHistory()), mainFlowGuide),
                        budget);
            } else {
                finalReply = SpeakTextLimiter.limit(
                        ForcedHangupRules.knowledgeNoMatchFallbackReply(), budget);
            }
            log.info("[LLM] 应答为空，使用兜底 recordId={}", req.getCallRecordId());
        }

        AiChatResponse r = new AiChatResponse();
        r.setReply(finalReply);
        r.setShouldHangup(endCall);
        if (endCall) {
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

    private int speakBudget() {
        return Math.max(aiVoiceProperties.getMaxSpeakChars(), 40);
    }

    private static String stripLeadingWeakAck(String script) {
        if (!StringUtils.hasText(script)) {
            return script;
        }
        return script.trim().replaceFirst("^(好的[，,]?|嗯[嗯]?[，,]?|哦[，,]?)+", "").trim();
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
        if (ForcedHangupRules.isLatencyComplaint(userText)) {
            return ForcedHangupRules.latencyComplaintReply();
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

        // 客户已报额度/时间，禁止只回「行/好」——必须带下一问
        if (isWeakAckOnlyReply(r) && slots != null && (slots.hasAmount || slots.hasTime)) {
            String alt = DialogSlotHelper.nextReply(slots, user, req.getHistory());
            if (StringUtils.hasText(alt)) {
                log.info("[LLM] 弱承接替换为槽位追问 recordId={} raw={}", req.getCallRecordId(), r);
                return alt;
            }
        }

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

    private static boolean isWeakAckOnlyReply(String reply) {
        if (!StringUtils.hasText(reply)) {
            return true;
        }
        String n = reply.trim().replaceAll("[\\s，,。.!！?？~～、；;]+", "");
        if (n.isEmpty()) {
            return true;
        }
        if (n.length() > 6) {
            return false;
        }
        return n.equals("行") || n.equals("好") || n.equals("好的") || n.equals("嗯") || n.equals("嗯嗯")
                || n.equals("哦") || n.equals("明白") || n.equals("了解") || n.equals("记下了")
                || n.equals("好嘞") || n.equals("可以") || n.equals("收到") || n.equalsIgnoreCase("ok");
    }

    private static boolean isEchoOnlyReply(String user, String reply, DialogSlotHelper.Slots slots) {
        if (!StringUtils.hasText(reply)) {
            return true;
        }
        if (isWeakAckOnlyReply(reply)) {
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
            // 短句且已有额度/时间：仍可能是「行」类，上面已拦；其它短实质答复放行
            return false;
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

    /**
     * 流式 TTS 路径轻量润色：只做告别/额度纠错与截断，避免 diversify 改写导致首句重播。
     */
    private String polishReplyPreserveStream(AiChatRequest req, String reply) {
        String user = req.getUserText() != null ? req.getUserText().trim() : "";
        if (ForcedHangupRules.isUserFarewell(user)) {
            return DialogSlotHelper.goodbyeReply(req.getHistory());
        }
        String polished = StringUtils.hasText(reply) ? reply : "";
        DialogSlotHelper.Slots slots = DialogSlotHelper.extract(req.getHistory(), req.getUserText());
        polished = DialogSlotHelper.correctUnclearReplyWhenAmountKnown(
                polished, slots, user, req.getHistory());
        return trimFragmentedReply(polished);
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
        // 超时/失败：优先主线当前题承接，避免干播「系统无法解答」
        if (req.getCallRecordId() != null && aiVoiceProperties.isDialogLlmPrimary()) {
            try {
                DialogCallContext ctx = dialogCallContextService.resolve(req.getCallRecordId());
                int kbId = ctx.hasKb() ? ctx.getKbId() : DialogCallContextService.DEFAULT_KB_ID;
                if (dialogMainFlowService.isEnabled(kbId)) {
                    dialogMainFlowService.ensureInit(req.getCallRecordId(), kbId);
                    String guide = dialogMainFlowService.resumeAfterFallback(req.getCallRecordId());
                    if (StringUtils.hasText(guide)) {
                        String bridged = MainFlowContextBridge.bridge(
                                req.getUserText(), lastAssistantText(req.getHistory()), guide);
                        log.info("[对话LLM] 超时主线兜底 recordId={} guideLen={}",
                                req.getCallRecordId(), guide.length());
                        return SpeakTextLimiter.limit(bridged, speakBudget());
                    }
                }
            } catch (Exception e) {
                log.debug("[对话LLM] 主线兜底跳过 recordId={}: {}", req.getCallRecordId(), e.getMessage());
            }
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

    /**
     * 主线推进：先按客户答案做规则承接；内容较具体时再让 LLM 润色一句（失败则回落规则版）。
     */
    private AiChatResponse buildMainFlowTurnReply(AiChatRequest req, String mainLine,
                                                  AiModelConfig modelCfg, long start) {
        String lastAi = lastAssistantText(req.getHistory());
        String bridged = MainFlowContextBridge.bridge(req.getUserText(), lastAi, mainLine);
        int speakChars = Math.max(aiVoiceProperties.getMaxSpeakChars(), 40);
        if (aiVoiceProperties.isDialogLlmPrimary()
                && MainFlowContextBridge.shouldLlmPolish(req.getUserText())) {
            try {
                String polished = polishMainFlowWithLlm(req, lastAi, mainLine, speakChars, modelCfg);
                if (StringUtils.hasText(polished)) {
                    log.info("[主线] 上下文润色 recordId={} rawLen={} polishLen={}",
                            req.getCallRecordId(),
                            bridged != null ? bridged.length() : 0,
                            polished.length());
                    return buildQuickReply(req, SpeakTextLimiter.limit(polished, speakChars),
                            modelCfg, start, false);
                }
            } catch (Exception e) {
                log.warn("[主线] 上下文润色失败，回落规则承接 recordId={}: {}",
                        req.getCallRecordId(), e.getMessage());
            }
        }
        return buildQuickReply(req, SpeakTextLimiter.limit(bridged, speakChars), modelCfg, start, false);
    }

    private String polishMainFlowWithLlm(AiChatRequest req, String lastAi, String mainLine,
                                         int speakChars, AiModelConfig modelCfg) {
        String system = MainFlowContextBridge.polishSystemHint(
                req.getUserText(), lastAi, mainLine, speakChars);
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", system));
        // 仅带最近 2 轮，降低延迟
        if (req.getHistory() != null && !req.getHistory().isEmpty()) {
            int from = Math.max(0, req.getHistory().size() - 4);
            for (int i = from; i < req.getHistory().size(); i++) {
                AiChatMessage m = req.getHistory().get(i);
                if (m == null || !StringUtils.hasText(m.getRole()) || !StringUtils.hasText(m.getContent())) {
                    continue;
                }
                String role = "assistant".equalsIgnoreCase(m.getRole()) ? "assistant" : "user";
                messages.add(Map.of("role", role, "content", m.getContent().trim()));
            }
        }
        messages.add(Map.of("role", "user", "content", req.getUserText().trim()));
        AiModelConfig llmCfg = cappedModelCfg(modelCfg);
        if (llmCfg.getMaxTokens() == null || llmCfg.getMaxTokens() < 48) {
            AiModelConfig copy = new AiModelConfig();
            org.springframework.beans.BeanUtils.copyProperties(llmCfg, copy);
            copy.setMaxTokens(48);
            llmCfg = copy;
        }
        String raw = llmInvokeService.chat(messages, llmCfg);
        String cleaned = forcedHangupService.stripHangupMarkers(raw);
        if (!StringUtils.hasText(cleaned)) {
            return null;
        }
        // 润色结果若完全丢掉主线问句关键词，回落规则版
        String core = mainLine == null ? "" : mainLine.replaceAll("[\\s，,。.!！?？~～]+", "");
        String out = cleaned.trim();
        if (core.length() >= 4) {
            String probe = core.substring(0, Math.min(4, core.length()));
            if (!out.contains(probe) && !out.contains("吗") && !out.contains("呢") && !out.contains("？")) {
                return null;
            }
        }
        return out;
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
        // AI 外呼：仅主线结束语（含再见）可挂机；槽位齐不自动挂
        boolean endCall = isMainFlowHangupReply(req, reply);
        if (!aiVoiceProperties.isDialogLlmPrimary()) {
            endCall = endCall || DialogSlotHelper.shouldEndCall(req.getUserText(), slots, req.getHistory());
        }
        if (DialogSlotHelper.shouldBypassRag(req.getUserText())) {
            endCall = false;
        }
        String polished = keywordFallback ? reply : DialogSlotHelper.diversifyIfNeeded(reply, slots, req.getUserText(), req.getHistory());
        String body = polished != null ? polished : reply;
        String finalReply = endCall
                ? DialogSlotHelper.goodbyeReply(req.getHistory())
                : SpeakTextLimiter.limit(body, speakBudget());

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
        int maxChars = Math.max(aiVoiceProperties.getMaxSpeakChars(), 40);
        sb.append("\n\n【说话风格】你是电话里的真人信贷顾问，必须全程口语聊天，像微信语音说话：")
                .append("用「您/你、这边、大概、那、行、嗯」这类口头词；禁止书面腔和念稿腔。")
                .append("反例（禁止）：「请问您大概需要多少资金呢」「有几个问题需要了解一下」。")
                .append("正例（要用）：「那您大概要多少资金呢」「先问一下，您是上班还是做生意呀」。")
                .append("先接住客户本句，再自然往下聊；每次只说一句，不超过").append(maxChars).append("字；")
                .append("说完停等客户；一次只问一个问题。主线只是引导目标，不要照念原文。")
                .append("听不清就换个说法再问，禁止瞎猜、跳题、主动挂断或说再见。");
        sb.append("\n【实时对话】严格轮次；已通话").append(elapsedSeconds).append("秒（上限")
                .append(ForcedHangupRules.MAX_CALL_SECONDS).append("秒）。")
                .append("【当前优先】必须先直接回应客户本句「").append(currentUser.trim()).append("」。")
                .append("「没有/不用」：上一问是有车/有房/社保等 → 按否定资质接话；上一问是要不要贷款 → 温和挽回，不要立刻再见。")
                .append("问利率/额度/公司身份：先简明作答再追问。")
                .append("纯数字在问额度场景按「X万」理解。");
        if (history != null && !history.isEmpty()) {
            sb.append("开场白已播过，不要重复开场问法；针对客户最新一句回应。");
        }
        sb.append("客户已说清的信息不要再问。");
        if (aiVoiceProperties.isDialogLlmPrimary() && history != null && !history.isEmpty()) {
            sb.append("\n\n【上下文】下方消息为完整本通对话，请结合历史回复，勿重复已问过的问题。");
        } else if (history != null && !history.isEmpty()) {
            sb.append("\n\n【完整对话记录】\n");
            appendTranscript(sb, history);
            sb.append("客户：").append(currentUser.trim());
        }
        sb.append("禁止输出 [挂断触发]；挂机由系统判定（辱骂投诉、强硬勿扰、满5分钟）。日常回复不要用结束语。")
                .append("参考结束语（仅系统挂断）：")
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
                "通话满10分钟 → 说明情况后礼貌结束"));
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
        int llmTimeoutSec = Math.max(5, Math.min(60, aiVoiceProperties.getDialogLlmTimeoutSec()));
        // HTTP 读超时对齐回合 LLM 超时，避免 60s 空转被 8s 线程池掐断
        int alignedReadTimeoutMs = (llmTimeoutSec + 2) * 1000;
        boolean needCopy = (cap > 0 && cfg.getMaxTokens() != null && cfg.getMaxTokens() > cap)
                || tempOverride != null
                || (topPOverride != null && topPOverride > 0 && topPOverride < 1.0)
                || cfg.getReadTimeoutMs() == null
                || cfg.getReadTimeoutMs() > alignedReadTimeoutMs;
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
        if (cfg.getReadTimeoutMs() == null || cfg.getReadTimeoutMs() > alignedReadTimeoutMs) {
            c.setReadTimeoutMs(alignedReadTimeoutMs);
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
