package com.aicall.service;



import com.aicall.common.DialogScriptKeywordMatcher;
import com.aicall.common.DialogSlotHelper;
import com.aicall.common.DialogTrainingIntentRouter;
import com.aicall.common.ForcedHangupRules;

import com.aicall.dto.AiChatMessage;

import com.aicall.dto.KbMatchCandidate;

import com.aicall.dto.DialogRagHitDto;

import com.aicall.dto.DialogRagRetrieveResult;

import com.aicall.dto.PrerecordTurnResultDto;

import com.aicall.entity.DialogTrainingQa;

import com.aicall.mapper.DialogTrainingQaMapper;

import com.aicall.util.DialogTranscriptLog;

import lombok.RequiredArgsConstructor;

import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;

import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 智能预录外呼：ASR 后优先 FAQ 录音应答；未命中或客户短答/同意时按主线循序推进（额度、征信等）。
 */
@Slf4j

@Service

@RequiredArgsConstructor

public class KbRecordingOutboundService {



    private static final ThreadLocal<PrerecordTurnResultDto> TRAINING_AUDIO_SINK = new ThreadLocal<>();

    private final DialogRagRetrievalService dialogRagRetrievalService;

    private final DialogTrainingQaMapper dialogTrainingQaMapper;

    private final DialogTrainingQaService dialogTrainingQaService;

    private final DialogMainFlowService dialogMainFlowService;

    private final DialogScriptPackRegistry dialogScriptPackRegistry;

    private final RecordingOnlyPlaybackService recordingOnlyPlaybackService;

    private final CallPlaybackDedupService callPlaybackDedupService;

    private final DialogKbMatchService dialogKbMatchService;

    private final KbAnswerWavCacheService kbAnswerWavCacheService;

    private final CallDialogPersistService callDialogPersistService;



    /**

     * @param allowAiFallback true=未命中时可回退 AI 实时（熔断场景）；false=全程纯录音模式

     */

    public PrerecordTurnResultDto handleTurn(String fsUuid, Integer callRecordId, String phone,

                                             String userText, int kbId, boolean allowAiFallback) throws Exception {

        return handleTurnInternal(fsUuid, callRecordId, phone, userText, kbId, allowAiFallback);

    }

    /** 管理端语音训练：不走 FreeSWITCH，仅解析应答录音 URL */
    public PrerecordTurnResultDto handleTrainingTurn(String trainTag, Integer callRecordId,

                                                      String userText, int kbId) throws Exception {

        PrerecordTurnResultDto audioSink = new PrerecordTurnResultDto();

        TRAINING_AUDIO_SINK.set(audioSink);

        try {

            PrerecordTurnResultDto result = handleTurnInternal(

                    trainTag, callRecordId, null, userText, kbId, false);

            if (StringUtils.hasText(audioSink.getReplyAudioUrl())) {

                result.setReplyAudioUrl(audioSink.getReplyAudioUrl());

            }

            return result;

        } finally {

            TRAINING_AUDIO_SINK.remove();

        }

    }

    private PrerecordTurnResultDto handleTurnInternal(String fsUuid, Integer callRecordId, String phone,

                                             String userText, int kbId, boolean allowAiFallback) throws Exception {

        PrerecordTurnResultDto result = new PrerecordTurnResultDto();

        result.setHandled(false);

        if (!StringUtils.hasText(fsUuid) || callRecordId == null || !StringUtils.hasText(userText)) {

            return result;

        }

        dialogMainFlowService.ensureInit(callRecordId, kbId);

        String lastAi = lastAssistantLine(callRecordId);

        if (ForcedHangupRules.isAbuseVulgarOrComplaint(userText)) {
            return playComplaintAndEnd(fsUuid, callRecordId, kbId, result);
        }

        if (ForcedHangupRules.isDisruptiveOrAbsurdRequest(userText)) {
            return playFallbackFaqAndHangup(fsUuid, callRecordId, kbId, 85, result);
        }

        PrerecordTurnResultDto openingIntent = tryHandleOpeningIntentResponse(
                fsUuid, callRecordId, userText, kbId, result);
        if (openingIntent.isHandled()) {
            return openingIntent;
        }

        if (ForcedHangupRules.declinesWeChatInvitationOnly(userText, lastAi)) {
            return playAfterWeChatDecline(fsUuid, callRecordId, kbId, result, lastAi);
        }

        PrerecordTurnResultDto trainingIntent = tryTrainingIntentRoute(
                fsUuid, callRecordId, userText, kbId, result);
        if (trainingIntent.isHandled()) {
            return trainingIntent;
        }

        if (ForcedHangupRules.isAsrCorrectionOrRetraction(userText)) {
            PrerecordTurnResultDto replay = tryResumeCurrentMainFlow(
                    fsUuid, callRecordId, kbId, result, "kb-asr-correction-replay");
            if (replay.isHandled()) {
                return replay;
            }
            return tryPlayHearingNudge(fsUuid, callRecordId, userText, kbId, result);
        }

        if (shouldHardRefuseAndHangup(callRecordId, userText)) {

            return playRefuseAndEnd(fsUuid, callRecordId, kbId, result);

        }

        if (ForcedHangupRules.isHearingIssue(userText)) {

            PrerecordTurnResultDto hearing = tryPlayHearingNudge(fsUuid, callRecordId, userText, kbId, result);

            if (hearing.isHandled()) {

                return hearing;

            }

        }



        if (shouldPrioritizeMainFlow(callRecordId, userText, kbId)) {

            PrerecordTurnResultDto mainFirst = tryPlayMainFlowLine(fsUuid, callRecordId, userText, kbId, result);

            if (mainFirst.isHandled()) {

                return mainFirst;

            }

        }

        PrerecordTurnResultDto repeatFollowUp = tryRepeatConcernFollowUp(
                fsUuid, callRecordId, userText, kbId, result);
        if (repeatFollowUp.isHandled()) {
            return repeatFollowUp;
        }

        PrerecordTurnResultDto faq = tryFaqMatch(fsUuid, callRecordId, userText, kbId, result, allowAiFallback);

        if (faq.isHandled()) {

            return faq;

        }

        if (DialogSlotHelper.isBeyondKnowledgeBaseScope(userText)) {

            return playTeacherEscalationFallback(fsUuid, callRecordId, kbId, result);

        }

        boolean explicitQuestion = DialogSlotHelper.isExplicitCustomerQuestion(userText);

        if (dialogMainFlowService.isEnabled(kbId) && shouldContinueMainFlow(userText, explicitQuestion, lastAi)) {

            PrerecordTurnResultDto main = tryPlayMainFlowLine(fsUuid, callRecordId, userText, kbId, result);

            if (main.isHandled()) {

                return main;

            }

        }

        if (ForcedHangupRules.isHearingIssue(userText)) {

            return tryPlayHearingNudge(fsUuid, callRecordId, userText, kbId, result);

        }

        return playTeacherEscalationFallback(fsUuid, callRecordId, kbId, result);

    }

    /** 拒绝加微信 ≠ 拒绝贷款：继续电话主线或转产品老师 */
    private PrerecordTurnResultDto playAfterWeChatDecline(String fsUuid, Integer callRecordId, int kbId,
                                                            PrerecordTurnResultDto result, String lastAi)
            throws Exception {
        String line = dialogMainFlowService.continueAfterWeChatDecline(callRecordId, kbId, lastAi);
        if (!StringUtils.hasText(line)) {
            PrerecordTurnResultDto aa = playMainFlowStepWav(fsUuid, callRecordId, kbId, "AA", result, "kb-wechat-decline-aa");
            if (aa.isHandled()) {
                return aa;
            }
            return playTeacherEscalationFallback(fsUuid, callRecordId, kbId, result);
        }
        String step = dialogMainFlowService.currentStep(callRecordId);
        String wavPath = resolvePlayableWav(kbId, line, null, step);
        if (playRecordedLine(fsUuid, callRecordId, line, wavPath, kbId)) {
            DialogTranscriptLog.aiReply(callRecordId, fsUuid, line, "kb-wechat-decline-continue", false);
            result.setHandled(true);
            result.setReplyText(line);
            result.setModel("kb-wechat-decline-continue:" + step);
            result.setPlaybackWaitHandled(true);
            log.info("[知识库录音] 拒绝加微后继续电话沟通 uuid={} step={}", fsUuid, step);
            return result;
        }
        return playTeacherEscalationFallback(fsUuid, callRecordId, kbId, result);
    }

    /**
     * 智能预录未应答或应答无声音时自动恢复：主线当前步 → 产品老师兜底 → 主线 AA。
     */
    public PrerecordTurnResultDto recoverUnhandledTurn(String fsUuid, Integer callRecordId,
                                                         String userText, int kbId) throws Exception {
        PrerecordTurnResultDto result = new PrerecordTurnResultDto();
        if (ForcedHangupRules.isAbuseVulgarOrComplaint(userText)) {
            return playComplaintAndEnd(fsUuid, callRecordId, kbId, result);
        }
        if (ForcedHangupRules.isDisruptiveOrAbsurdRequest(userText)) {
            return playFallbackFaqAndHangup(fsUuid, callRecordId, kbId, 85, result);
        }
        PrerecordTurnResultDto resumed = tryResumeCurrentMainFlow(
                fsUuid, callRecordId, kbId, result, "kb-auto-resume-main");
        if (resumed.isHandled()) {
            return resumed;
        }
        if (dialogMainFlowService.isEnabled(kbId) && shouldContinueMainFlow(userText, false, lastAssistantLine(callRecordId))) {
            PrerecordTurnResultDto main = tryPlayMainFlowLine(fsUuid, callRecordId, userText, kbId, result);
            if (main.isHandled()) {
                return main;
            }
        }
        PrerecordTurnResultDto teacher = playTeacherEscalationFallback(fsUuid, callRecordId, kbId, result);
        if (teacher.isHandled()) {
            return teacher;
        }
        return playMainFlowStepWav(fsUuid, callRecordId, kbId, "AA", result, "kb-auto-resume-aa");
    }

    public static boolean isSilentPrerecordResult(PrerecordTurnResultDto pre) {
        if (pre == null || !pre.isHandled()) {
            return false;
        }
        String model = pre.getModel();
        return model != null && model.contains("-silent");
    }

    private PrerecordTurnResultDto tryResumeCurrentMainFlow(String fsUuid, Integer callRecordId, int kbId,
                                                              PrerecordTurnResultDto result, String modelTag) {
        if (!dialogMainFlowService.isEnabled(kbId)) {
            return result;
        }
        String line = dialogMainFlowService.resumeAfterFallback(callRecordId);
        if (!StringUtils.hasText(line)) {
            return result;
        }
        String step = dialogMainFlowService.currentStep(callRecordId);
        String wavPath = resolvePlayableWav(kbId, line, null, step);
        try {
            if (playRecordedLine(fsUuid, callRecordId, line, wavPath, kbId)) {
                DialogTranscriptLog.aiReply(callRecordId, fsUuid, line, modelTag, false);
                result.setHandled(true);
                result.setReplyText(line);
                result.setModel(modelTag + ":" + step);
                result.setPlaybackWaitHandled(true);
                log.info("[知识库录音] 自动恢复主线 uuid={} step={}", fsUuid, step);
            }
        } catch (Exception e) {
            log.warn("[知识库录音] 自动恢复主线失败 uuid={} step={}: {}", fsUuid, step, e.getMessage());
        }
        return result;
    }

    private PrerecordTurnResultDto playMainFlowStepWav(String fsUuid, Integer callRecordId, int kbId,
                                                         String flowStep, PrerecordTurnResultDto result,
                                                         String modelTag) {
        String line = dialogScriptPackRegistry.mainFlowScript(flowStep, kbId);
        if (!StringUtils.hasText(line)) {
            return result;
        }
        String wavPath = resolvePlayableWav(kbId, line, null, flowStep);
        try {
            if (playRecordedLine(fsUuid, callRecordId, line, wavPath, kbId)) {
                DialogTranscriptLog.aiReply(callRecordId, fsUuid, line, modelTag, false);
                result.setHandled(true);
                result.setReplyText(line);
                result.setModel(modelTag);
                result.setPlaybackWaitHandled(true);
            }
        } catch (Exception e) {
            log.warn("[知识库录音] 播放主线步骤失败 uuid={} step={}: {}", fsUuid, flowStep, e.getMessage());
        }
        return result;
    }

    private String resolvePlayableWav(int kbId, String text, String wavPath, String flowStep) {
        return resolvePlayableWav(kbId, text, wavPath, flowStep, null);
    }

    private String resolvePlayableWav(int kbId, String text, String wavPath, String flowStep, Integer qaId) {
        if (qaId != null) {
            String byQa = kbAnswerWavCacheService.resolveByQaId(qaId);
            if (StringUtils.hasText(byQa)) {
                return byQa;
            }
        }
        String resolved = kbAnswerWavCacheService.resolveExplicit(wavPath);
        if (StringUtils.hasText(resolved)) {
            return resolved;
        }
        if (StringUtils.hasText(flowStep)) {
            DialogTrainingQa flowRow = dialogTrainingQaService.findByFlowStep(kbId, flowStep);
            if (flowRow != null) {
                resolved = kbAnswerWavCacheService.resolveByQaId(flowRow.getId());
                if (StringUtils.hasText(resolved)) {
                    return resolved;
                }
            }
            return null;
        }
        if (qaId != null) {
            return null;
        }
        if (StringUtils.hasText(text)) {
            resolved = kbAnswerWavCacheService.resolveByAnswerText(kbId, text.trim());
            if (StringUtils.hasText(resolved)) {
                return resolved;
            }
        }
        return null;
    }

    /** 开场白后客户无转贷/资金打算时播放的加微话术（FAQ 70） */
    private static final int OPENING_NO_INTENT_WECHAT_FAQ = 70;

    /**
     * 开场白（step 01）后：无打算 → 播结束录音并挂断；有打算 → 交给主线推进。
     */
    private PrerecordTurnResultDto tryHandleOpeningIntentResponse(String fsUuid, Integer callRecordId,
                                                                    String userText, int kbId,
                                                                    PrerecordTurnResultDto result) throws Exception {
        if (!dialogMainFlowService.isEnabled(kbId)) {
            return result;
        }
        if (!"01".equals(dialogMainFlowService.currentStep(callRecordId))) {
            return result;
        }
        String lastAi = lastAssistantLine(callRecordId);
        if (ForcedHangupRules.isAbuseVulgarOrComplaint(userText)
                || ForcedHangupRules.isDisruptiveOrAbsurdRequest(userText)) {
            return result;
        }
        if (!ForcedHangupRules.isOpeningFundingIntentQuestion(lastAi)) {
            return result;
        }
        if (!ForcedHangupRules.declinesOpeningFundingIntent(userText, lastAi)) {
            if (ForcedHangupRules.defersFundingNeed(userText)) {
                return playFallbackFaq(fsUuid, callRecordId, kbId, 90, result, false);
            }
            if (ForcedHangupRules.acceptsOpeningFundingIntent(userText)
                    || ForcedHangupRules.acceptsOpeningCooperativeResponse(userText)) {
                PrerecordTurnResultDto main = tryPlayMainFlowLine(fsUuid, callRecordId, userText, kbId, result);
                if (main.isHandled()) {
                    return main;
                }
                PrerecordTurnResultDto recovered = tryResumeCurrentMainFlow(
                        fsUuid, callRecordId, kbId, result, "kb-opening-accept-resume");
                if (recovered.isHandled()) {
                    return recovered;
                }
            }
            return result;
        }
        dialogMainFlowService.markNoIntentAtOpening(callRecordId);
        return playFallbackFaq(fsUuid, callRecordId, kbId, OPENING_NO_INTENT_WECHAT_FAQ, result, false);
    }

    /** 训练文档专项意图：转贷/同行/办理咨询/逾期等，优先于主线 */
    private PrerecordTurnResultDto tryTrainingIntentRoute(String fsUuid, Integer callRecordId,
                                                          String userText, int kbId,
                                                          PrerecordTurnResultDto result) throws Exception {
        String step = dialogMainFlowService.currentStep(callRecordId);
        String lastAi = lastAssistantLine(callRecordId);
        DialogTrainingIntentRouter.IntentRoute route =
                DialogTrainingIntentRouter.resolveFaqRoute(userText, lastAi, step);
        if (route == null) {
            return result;
        }
        return playFallbackFaq(fsUuid, callRecordId, kbId, route.faqFallbackNo(), result, route.hangup());
    }

    /** 播放 FAQ 兜底（fallback:no） */
    private PrerecordTurnResultDto playFallbackFaq(String fsUuid, Integer callRecordId, int kbId,
                                                   int fallbackNo, PrerecordTurnResultDto result,
                                                   boolean shouldHangup) throws Exception {
        DialogTrainingQa faq = findFallbackQa(kbId, fallbackNo);
        if (faq == null || !StringUtils.hasText(faq.getStandardAnswer())) {
            if (shouldHangup) {
                return playRefuseAndEnd(fsUuid, callRecordId, kbId, result);
            }
            return result;
        }
        String line = faq.getStandardAnswer().trim();
        String wavPath = resolvePlayableWav(kbId, line, faq.getAnswerWavPath(), null, faq.getId());
        if (playRecordedLine(fsUuid, callRecordId, line, wavPath, kbId, faq.getId())) {
            DialogTranscriptLog.aiReply(callRecordId, fsUuid, line, "kb-fallback:" + fallbackNo, shouldHangup);
            result.setHandled(true);
            result.setShouldHangup(shouldHangup);
            result.setReplyText(line);
            result.setModel("kb-fallback:" + fallbackNo);
            result.setPlaybackWaitHandled(true);
        } else if (shouldHangup) {
            return playRefuseAndEnd(fsUuid, callRecordId, kbId, result);
        } else if (isTrainingMode()) {
            result.setHandled(true);
            result.setShouldHangup(shouldHangup);
            result.setReplyText(line);
            result.setModel("kb-fallback:" + fallbackNo + (isTrainingMode() ? "-silent" : "-text"));
        }
        return result;
    }

    private PrerecordTurnResultDto playFallbackFaqAndHangup(String fsUuid, Integer callRecordId, int kbId,
                                                              int fallbackNo, PrerecordTurnResultDto result)
            throws Exception {
        return playFallbackFaq(fsUuid, callRecordId, kbId, fallbackNo, result, true);
    }

    private PrerecordTurnResultDto playComplaintAndEnd(String fsUuid, Integer callRecordId, int kbId,
                                                         PrerecordTurnResultDto result) throws Exception {
        dialogMainFlowService.markNoIntentAtOpening(callRecordId);
        PrerecordTurnResultDto faq = playFallbackFaq(fsUuid, callRecordId, kbId, 145, result, true);
        if (faq.isHandled()) {
            return faq;
        }
        String line = ForcedHangupRules.complaintSoothingEndWords();
        if (playRecordedLine(fsUuid, callRecordId, line, null, kbId, null)) {
            result.setPlaybackWaitHandled(true);
        }
        DialogTranscriptLog.aiReply(callRecordId, fsUuid, line, "kb-complaint-end", true);
        result.setHandled(true);
        result.setShouldHangup(true);
        result.setReplyText(line);
        result.setModel("kb-complaint-end");
        return result;
    }

    /** 结合上一轮 AI 语境，避免「有车吗→没有」「上班吗→不用」误判为拒接挂断 */
    private boolean shouldHardRefuseAndHangup(Integer callRecordId, String userText) {
        String lastAi = lastAssistantLine(callRecordId);
        if (!ForcedHangupRules.wantsNoDisturbance(userText, lastAi)) {
            return false;
        }
        if (ForcedHangupRules.hasBusinessIntent(userText)
                || ForcedHangupRules.isCooperativeAnswer(userText)) {
            return false;
        }
        if (ForcedHangupRules.isAssetQualificationQuestion(lastAi)) {
            return false;
        }
        if (ForcedHangupRules.isWeChatInvitationContext(lastAi)) {
            return false;
        }
        if (isFundingNeedContext(lastAi)) {
            String n = userText.trim().replaceAll("[\\s，,]+", "").replaceAll("[。.!！?？~～]+", "");
            if (n.equals("没有") || n.equals("没") || n.equals("没有啊") || n.equals("没啊")) {
                return false;
            }
        }
        String t = userText.trim();
        if (t.equals("不需要") || t.equals("不用")) {
            return lastAi.contains("资金") || lastAi.contains("备用") || lastAi.contains("转贷")
                    || lastAi.contains("别打") || lastAi.contains("打扰");
        }
        return true;
    }

    /** 短答/同意/开场想了解：FAQ 不应抢在主线之前 */
    private boolean shouldPrioritizeMainFlow(Integer callRecordId, String userText, int kbId) {
        if (!dialogMainFlowService.isEnabled(kbId)) {
            return false;
        }
        if (ForcedHangupRules.isAbuseVulgarOrComplaint(userText)
                || ForcedHangupRules.isDisruptiveOrAbsurdRequest(userText)) {
            return false;
        }
        if (DialogTrainingIntentRouter.isProcessingTimeInquiry(userText)
                || DialogTrainingIntentRouter.looksLikeNoTimeOrBusy(userText)) {
            return false;
        }
        String step = dialogMainFlowService.currentStep(callRecordId);
        if (DialogTrainingIntentRouter.mentionsProperty(userText)
                && ("04".equals(step) || "05".equals(step) || "06".equals(step) || "07".equals(step))) {
            return true;
        }
        if (ForcedHangupRules.deniesSocialInsuranceQualification(userText)
                && ("04".equals(step) || "05".equals(step) || "06".equals(step) || "07".equals(step))) {
            return true;
        }
        if (DialogTrainingIntentRouter.looksLikeHomemakerOrFlexibleJob(userText) && "03".equals(step)) {
            return true;
        }
        if (DialogTrainingIntentRouter.shouldDeferKeywordFaqToMainFlow(
                userText, lastAssistantLine(callRecordId), step)) {
            return true;
        }
        if (DialogSlotHelper.shouldPreferMainFlowAdvance(userText)) {
            return true;
        }
        if (DialogTrainingIntentRouter.blocksMainFlowAdvance(
                userText, lastAssistantLine(callRecordId), dialogMainFlowService.currentStep(callRecordId))) {
            return false;
        }
        if ("01".equals(dialogMainFlowService.currentStep(callRecordId))) {
            String lastAi = lastAssistantLine(callRecordId);
            if (ForcedHangupRules.isOpeningFundingIntentQuestion(lastAi)
                    && (ForcedHangupRules.declinesOpeningFundingIntent(userText, lastAi)
                    || ForcedHangupRules.defersFundingNeed(userText))) {
                return false;
            }
            if (ForcedHangupRules.isOpeningFundingIntentQuestion(lastAi)
                    && ForcedHangupRules.acceptsOpeningCooperativeResponse(userText)) {
                return true;
            }
        }
        return false;
    }

    private String lastAssistantLine(Integer callRecordId) {
        if (callRecordId == null) {
            return "";
        }
        List<AiChatMessage> hist = callDialogPersistService.loadChatHistory(callRecordId);
        for (int i = hist.size() - 1; i >= 0; i--) {
            AiChatMessage m = hist.get(i);
            if ("assistant".equalsIgnoreCase(m.getRole()) && StringUtils.hasText(m.getContent())) {
                return m.getContent().trim();
            }
        }
        return "";
    }

    /** 上一轮客户原话（排除本轮已入库的 userText） */
    private String previousUserLine(Integer callRecordId, String currentUserText) {
        if (callRecordId == null) {
            return "";
        }
        List<AiChatMessage> hist = callDialogPersistService.loadChatHistory(callRecordId);
        boolean skippedCurrent = false;
        for (int i = hist.size() - 1; i >= 0; i--) {
            AiChatMessage m = hist.get(i);
            if ("user".equalsIgnoreCase(m.getRole()) && StringUtils.hasText(m.getContent())) {
                String line = m.getContent().trim();
                if (!skippedCurrent && StringUtils.hasText(currentUserText)
                        && line.equals(currentUserText.trim())) {
                    skippedCurrent = true;
                    continue;
                }
                return line;
            }
        }
        return "";
    }

    private boolean isSameReplyAsLastAssistant(Integer callRecordId, String replyText) {
        if (!StringUtils.hasText(replyText)) {
            return false;
        }
        String last = lastAssistantLine(callRecordId);
        if (!StringUtils.hasText(last)) {
            return false;
        }
        return normalizeReplyKey(last).equals(normalizeReplyKey(replyText));
    }

    private static String normalizeReplyKey(String text) {
        return text.trim().replaceAll("[\\s，,。.!！?？~～；;]+", "");
    }

    /**
     * 客户重复同一顾虑且上轮已答过：换一条 FAQ 或继续主线，避免复读同一段录音。
     */
    private PrerecordTurnResultDto tryRepeatConcernFollowUp(String fsUuid, Integer callRecordId,
                                                            String userText, int kbId,
                                                            PrerecordTurnResultDto result) throws Exception {
        String prevUser = previousUserLine(callRecordId, userText);
        if (!DialogScriptKeywordMatcher.isSameTopicConcern(userText, prevUser)) {
            return result;
        }
        String lastAi = lastAssistantLine(callRecordId);
        if (!StringUtils.hasText(lastAi)) {
            return result;
        }
        log.info("[知识库录音] 客户重复同一顾虑 recordId={} user={} prevUser={}", callRecordId, userText, prevUser);
        if (DialogScriptKeywordMatcher.isCreditProblemStatement(userText)) {
            var altRule = dialogScriptPackRegistry.matchKeyword(userText, kbId);
            if (altRule != null && StringUtils.hasText(altRule.answer())
                    && !normalizeReplyKey(altRule.answer()).equals(normalizeReplyKey(lastAi))) {
                DialogTrainingQa qa = dialogTrainingQaMapper.selectById(altRule.id());
                String wav = qa != null ? qa.getAnswerWavPath() : null;
                if (playRecordedLine(fsUuid, callRecordId, altRule.answer().trim(), wav, kbId)) {
                    DialogTranscriptLog.aiReply(callRecordId, fsUuid, altRule.answer().trim(),
                            "kb-repeat-alt-faq", false);
                    result.setHandled(true);
                    result.setReplyText(altRule.answer().trim());
                    result.setModel("kb-repeat-alt-faq");
                    result.setPlaybackWaitHandled(true);
                    return result;
                }
            }
            DialogTrainingQa repeatFaq = findFallbackQa(kbId, 143);
            if (repeatFaq != null && StringUtils.hasText(repeatFaq.getStandardAnswer())
                    && !normalizeReplyKey(repeatFaq.getStandardAnswer()).equals(normalizeReplyKey(lastAi))) {
                String line = repeatFaq.getStandardAnswer().trim();
                if (playRecordedLine(fsUuid, callRecordId, line, repeatFaq.getAnswerWavPath(), kbId)) {
                    DialogTranscriptLog.aiReply(callRecordId, fsUuid, line, "kb-repeat-faq-143", false);
                    result.setHandled(true);
                    result.setReplyText(line);
                    result.setModel("kb-repeat-faq-143");
                    result.setPlaybackWaitHandled(true);
                    return result;
                }
            }
        }
        if (dialogMainFlowService.isEnabled(kbId)) {
            PrerecordTurnResultDto main = tryPlayMainFlowLine(fsUuid, callRecordId, userText, kbId, result);
            if (main.isHandled()) {
                return main;
            }
            PrerecordTurnResultDto resumed = tryResumeCurrentMainFlow(
                    fsUuid, callRecordId, kbId, result, "kb-repeat-resume-main");
            if (resumed.isHandled()) {
                return resumed;
            }
        }
        return result;
    }

    private DialogTrainingQa findFallbackQa(int kbId, int fallbackNo) {
        return dialogTrainingQaMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<DialogTrainingQa>()
                        .eq(DialogTrainingQa::getKbId, kbId)
                        .eq(DialogTrainingQa::getRemark, "fallback:" + fallbackNo)
                        .eq(DialogTrainingQa::getStatus, 1)
                        .last("LIMIT 1"));
    }

    private static boolean isFundingNeedContext(String lastAssistantText) {
        if (!StringUtils.hasText(lastAssistantText)) {
            return false;
        }
        String ai = lastAssistantText.trim();
        return ai.contains("资金") || ai.contains("周转") || ai.contains("贷款")
                || ai.contains("用款") || ai.contains("融资") || ai.contains("需求")
                || ai.contains("多少万") || ai.contains("多少资金") || ai.contains("期望额度");
    }

    private static boolean shouldContinueMainFlow(String userText, boolean explicitQuestion, String lastAssistantText) {

        if (ForcedHangupRules.isDisruptiveOrAbsurdRequest(userText)
                || ForcedHangupRules.isAbuseVulgarOrComplaint(userText)) {
            return false;
        }
        if (DialogTrainingIntentRouter.isProcessingTimeInquiry(userText)
                || DialogTrainingIntentRouter.looksLikeNoTimeOrBusy(userText)) {
            return false;
        }
        if (ForcedHangupRules.isOpeningFundingIntentQuestion(lastAssistantText)
                && (ForcedHangupRules.declinesOpeningFundingIntent(userText, lastAssistantText)
                || ForcedHangupRules.defersFundingNeed(userText))) {
            return false;
        }

        if (DialogSlotHelper.shouldPreferMainFlowAdvance(userText)) {

            return true;

        }

        if (explicitQuestion || ForcedHangupRules.shouldSkipSlotOverride(userText)) {

            return false;

        }

        return ForcedHangupRules.hasBusinessIntent(userText)

                || ForcedHangupRules.isCooperativeAnswer(userText)

                || userText.trim().length() <= 8;

    }

    private PrerecordTurnResultDto tryFaqMatch(String fsUuid, Integer callRecordId, String userText,

                                               int kbId, PrerecordTurnResultDto result,

                                               boolean allowAiFallback) throws Exception {

        String lastAi = lastAssistantLine(callRecordId);
        String step = dialogMainFlowService.currentStep(callRecordId);
        List<KbMatchCandidate> candidates = dialogKbMatchService.rankCandidates(userText, kbId);

        for (KbMatchCandidate candidate : candidates) {

            if (!allowAiFallback && !candidate.isPlayable()) {

                continue;

            }

            if (isSameReplyAsLastAssistant(callRecordId, candidate.getReplyText())) {

                log.info("[知识库录音] 跳过与上轮相同 FAQ recordId={} qaId={}", callRecordId, candidate.getQaId());

                continue;

            }

            if (!dialogKbMatchService.shouldPlay(candidate, userText, lastAi, step)) {

                continue;

            }

            PrerecordTurnResultDto played = playCandidate(fsUuid, callRecordId, candidate,

                    result, allowAiFallback, kbId);

            if (played.isHandled()) {

                return played;

            }

        }

        if (dialogMainFlowService.isEnabled(kbId)
                && DialogTrainingIntentRouter.shouldDeferKeywordFaqToMainFlow(userText, lastAi, step)) {
            PrerecordTurnResultDto main = tryPlayMainFlowLine(fsUuid, callRecordId, userText, kbId, result);
            if (main.isHandled()) {
                return main;
            }
        }

        return result;

    }



    private PrerecordTurnResultDto tryPlayMainFlowLine(String fsUuid, Integer callRecordId, String userText,

                                                        int kbId, PrerecordTurnResultDto result) throws Exception {

        String prevStep = dialogMainFlowService.currentStep(callRecordId);

        String line = dialogMainFlowService.nextMainLineAfterUser(
                callRecordId, userText, lastAssistantLine(callRecordId));

        if (!StringUtils.hasText(line)) {

            return result;

        }

        String step = dialogMainFlowService.currentStep(callRecordId);

        DialogTrainingQa flowRow = dialogTrainingQaService.findByFlowStep(kbId, step);

        String wavPath = resolvePlayableWav(kbId, line, flowRow != null ? flowRow.getAnswerWavPath() : null, step,
                flowRow != null ? flowRow.getId() : null);

        if (callPlaybackDedupService.shouldSkip(callRecordId, line, wavPath)) {

            log.info("[知识库录音] 跳过重复主线录音 recordId={} step={}，回退步骤", callRecordId, step);

            dialogMainFlowService.restoreStep(callRecordId, prevStep);

            return result;

        }

        boolean played = playRecordedLine(fsUuid, callRecordId, line, wavPath, kbId,
                flowRow != null ? flowRow.getId() : null);

        if (!played) {

            dialogMainFlowService.restoreStep(callRecordId, prevStep);

            log.warn("[知识库录音] 主线无可用录音 uuid={} step={} kb={}，已回退步骤", fsUuid, step, kbId);

            return result;

        }

        DialogTranscriptLog.aiReply(callRecordId, fsUuid, line, "kb-main-flow", false);

        result.setHandled(true);

        result.setReplyText(line);

        result.setModel("kb-main-flow:" + step);

        result.setPlaybackWaitHandled(true);

        log.info("[知识库录音] 主线推进 uuid={} kb={} step={} user={}",

                fsUuid, kbId, step,

                userText.length() > 20 ? userText.substring(0, 20) + "…" : userText);

        return result;

    }



    private PrerecordTurnResultDto playCandidate(String fsUuid, Integer callRecordId, KbMatchCandidate candidate,

                                                 PrerecordTurnResultDto result, boolean allowAiFallback,

                                                 int kbId) throws Exception {

        String replyText = candidate.getReplyText().trim();

        String wavPath = candidate.getWavPath();
        if (!StringUtils.hasText(wavPath) && candidate.getQaId() != null) {
            wavPath = kbAnswerWavCacheService.resolveByQaId(candidate.getQaId());
        }

        if (callPlaybackDedupService.shouldSkip(callRecordId, replyText, wavPath)) {

            log.info("[知识库录音] 跳过重复 FAQ 录音 recordId={} source={}，尝试下一候选", callRecordId, candidate.getSource());

            return result;

        }

        boolean played = playRecordedLine(fsUuid, callRecordId, replyText, wavPath, kbId, candidate.getQaId());

        if (!played) {

            if (allowAiFallback) {

                log.info("[知识库录音] 候选无可用录音 uuid={} qaId={} source={}，回退 AI",

                        fsUuid, candidate.getQaId(), candidate.getSource());

                return result;

            }

            if (isTrainingMode() && candidate.isKeywordMatched()) {
                DialogTranscriptLog.aiReply(callRecordId, fsUuid, replyText,
                        "kb-recording:" + candidate.getSource() + "-text", false);
                result.setHandled(true);
                result.setReplyText(replyText);
                result.setModel("kb-recording:" + candidate.getSource() + "-text");
                return result;
            }

            log.warn("[知识库录音] 候选无录音 uuid={} qaId={} source={} score={}，尝试下一候选",

                    fsUuid, candidate.getQaId(), candidate.getSource(), candidate.getScore());

            return result;

        }

        DialogTranscriptLog.aiReply(callRecordId, fsUuid, replyText,

                "kb-recording:" + candidate.getSource(), false);

        result.setHandled(true);

        result.setReplyText(replyText);

        result.setModel("kb-recording:" + candidate.getSource());

        result.setPlaybackWaitHandled(true);

        log.info("[知识库录音] FAQ应答 uuid={} qaId={} source={} playable={} score={}",

                fsUuid, candidate.getQaId(), candidate.getSource(), candidate.isPlayable(), candidate.getScore());

        return result;

    }



    private PrerecordTurnResultDto playAnswer(String fsUuid, Integer callRecordId, DialogRagRetrieveResult rag,

                                              String replyText, PrerecordTurnResultDto result,

                                              boolean allowAiFallback, int kbId) throws Exception {

        DialogRagHitDto best = rag.bestPositive();

        String wavPath = null;

        if (rag.getKeywordRuleId() != null) {

            DialogTrainingQa kwQa = dialogTrainingQaMapper.selectById(rag.getKeywordRuleId());

            if (kwQa != null && StringUtils.hasText(kwQa.getAnswerWavPath())) {

                wavPath = kwQa.getAnswerWavPath();

            }

        }

        if (wavPath == null && best != null && best.getQaId() != null) {

            DialogTrainingQa qa = dialogTrainingQaMapper.selectById(best.getQaId());

            if (qa != null && StringUtils.hasText(qa.getAnswerWavPath())) {

                wavPath = qa.getAnswerWavPath();

            }

        }

        if (callPlaybackDedupService.shouldSkip(callRecordId, replyText, wavPath)) {

            log.info("[知识库录音] 跳过重复 FAQ 录音 recordId={}", callRecordId);

            return result;

        }

        boolean played = playRecordedLine(fsUuid, callRecordId, replyText, wavPath, kbId);

        if (!played) {

            if (allowAiFallback) {

                log.info("[知识库录音] 未命中可用录音 uuid={}，回退 AI", fsUuid);

                return result;

            }

            log.warn("[知识库录音] 命中但无录音 uuid={} qaId={}，尝试主线或兜底", fsUuid,

                    best != null ? best.getQaId() : null);

            return result;

        }

        DialogTranscriptLog.aiReply(callRecordId, fsUuid, replyText, "kb-recording", false);

        result.setHandled(true);

        result.setReplyText(replyText);

        result.setModel("kb-recording");

        result.setPlaybackWaitHandled(true);

        log.info("[知识库录音] FAQ应答 uuid={} qaId={} direct={} ms={}",

                fsUuid, best != null ? best.getQaId() : null, rag.isDirectAnswer(), rag.getRetrieveMs());

        return result;

    }



    private void attachTrainingAudioUrl(int kbId, String text, String wavPath) {

        PrerecordTurnResultDto trainingSink = TRAINING_AUDIO_SINK.get();

        if (trainingSink != null) {

            trainingSink.setReplyAudioUrl(resolveReplyAudioUrl(kbId, text, wavPath));

        }

    }

    private boolean playRecordedLine(String fsUuid, Integer callRecordId, String text,

                                     String wavPath, int kbId) throws Exception {
        return playRecordedLine(fsUuid, callRecordId, text, wavPath, kbId, null);
    }

    private boolean playRecordedLine(String fsUuid, Integer callRecordId, String text,

                                     String wavPath, int kbId, Integer qaId) throws Exception {

        PrerecordTurnResultDto trainingSink = TRAINING_AUDIO_SINK.get();

        if (trainingSink != null) {

            trainingSink.setReplyAudioUrl(resolveReplyAudioUrl(kbId, text, wavPath, qaId));

            if (StringUtils.hasText(trainingSink.getReplyAudioUrl()) && callRecordId != null) {

                callPlaybackDedupService.record(callRecordId, text, wavPath);

            }

            return StringUtils.hasText(trainingSink.getReplyAudioUrl());

        }

        boolean played = false;

        String resolvedWav = resolvePlayableWav(kbId, text, wavPath, null, qaId);

        if (StringUtils.hasText(resolvedWav)) {

            try {

                played = recordingOnlyPlaybackService.playWavPath(fsUuid, resolvedWav, text);

            } catch (Exception e) {

                log.warn("[知识库录音] wav 播放失败 uuid={}: {}", fsUuid, e.getMessage());

            }

        }

        if (!played) {

            played = recordingOnlyPlaybackService.playCachedPhrase(fsUuid, text, kbId);

        }

        if (played) {

            callPlaybackDedupService.record(callRecordId, text, resolvedWav);

        }

        return played;

    }

    private static boolean isTrainingMode() {

        return TRAINING_AUDIO_SINK.get() != null;

    }

    private String resolveReplyAudioUrl(int kbId, String text, String wavPath) {
        return resolveReplyAudioUrl(kbId, text, wavPath, null);
    }

    private String resolveReplyAudioUrl(int kbId, String text, String wavPath, Integer qaId) {

        if (qaId != null) {
            String byQa = kbAnswerWavCacheService.resolveByQaId(qaId);
            if (StringUtils.hasText(byQa)) {
                String url = RecordingOnlyPlaybackService.toPublicUrl(byQa);
                if (StringUtils.hasText(url)) {
                    return url;
                }
            }
        }

        if (StringUtils.hasText(wavPath)) {

            String url = RecordingOnlyPlaybackService.toPublicUrl(wavPath);

            if (StringUtils.hasText(url)) {

                return url;

            }

        }

        if (StringUtils.hasText(text) && qaId == null) {

            String cachedWav = kbAnswerWavCacheService.resolveByAnswerText(kbId, text.trim());

            if (StringUtils.hasText(cachedWav)) {

                String url = RecordingOnlyPlaybackService.toPublicUrl(cachedWav);

                if (StringUtils.hasText(url)) {

                    return url;

                }

            }

        }

        return null;

    }



    private PrerecordTurnResultDto tryPlayHearingNudge(String fsUuid, Integer callRecordId, String userText,

                                                        int kbId, PrerecordTurnResultDto result) throws Exception {

        DialogRagRetrieveResult kw = dialogRagRetrievalService.tryKeywordDirectAnswer(userText, kbId);

        String reply = kw.isKeywordMatched() && StringUtils.hasText(kw.getDirectAnswerText())

                ? kw.getDirectAnswerText().trim()

                : ForcedHangupRules.hearingFallbackReply();

        String wavPath = null;

        if (kw.getKeywordRuleId() != null) {

            DialogTrainingQa qa = dialogTrainingQaMapper.selectById(kw.getKeywordRuleId());

            if (qa != null && StringUtils.hasText(qa.getAnswerWavPath())) {

                wavPath = qa.getAnswerWavPath();

            }

        }

        if (playRecordedLine(fsUuid, callRecordId, reply, wavPath, kbId)) {

            DialogTranscriptLog.aiReply(callRecordId, fsUuid, reply, "kb-hearing-nudge", false);

            result.setHandled(true);

            result.setReplyText(reply);

            result.setModel("kb-hearing-nudge");

            result.setPlaybackWaitHandled(true);

            return result;

        }

        return result;

    }



    private PrerecordTurnResultDto playTeacherEscalationFallback(String fsUuid, Integer callRecordId, int kbId,

                                                                  PrerecordTurnResultDto result) {

        String fallback = null;

        String wavPath = null;

        DialogTrainingQa aaFlow = dialogTrainingQaService.findByFlowStep(kbId, "AA");

        if (aaFlow != null && StringUtils.hasText(aaFlow.getStandardAnswer())) {

            fallback = aaFlow.getStandardAnswer().trim();

            wavPath = aaFlow.getAnswerWavPath();

        } else {

            String aaScript = dialogScriptPackRegistry.mainFlowScript("AA", kbId);

            if (StringUtils.hasText(aaScript)) {

                fallback = aaScript.trim();

            }

        }

        if (!StringUtils.hasText(fallback)) {

            fallback = ForcedHangupRules.teacherEscalationFallbackReply();

        }

        if (!StringUtils.hasText(wavPath)) {

            DialogRagRetrieveResult kw = dialogRagRetrievalService.tryKeywordDirectAnswer("产品老师", kbId);

            if (kw.isKeywordMatched() && StringUtils.hasText(kw.getDirectAnswerText())) {

                fallback = kw.getDirectAnswerText().trim();

            } else {

                var rule = dialogScriptPackRegistry.matchKeyword("产品老师", kbId);

                if (rule != null && StringUtils.hasText(rule.answer())) {

                    fallback = rule.answer().trim();

                    DialogTrainingQa qa = dialogTrainingQaMapper.selectById(rule.id());

                    if (qa != null && StringUtils.hasText(qa.getAnswerWavPath())) {

                        wavPath = qa.getAnswerWavPath();

                    }

                }

            }

            if (wavPath == null && kw.getKeywordRuleId() != null) {

                DialogTrainingQa qa = dialogTrainingQaMapper.selectById(kw.getKeywordRuleId());

                if (qa != null && StringUtils.hasText(qa.getAnswerWavPath())) {

                    wavPath = qa.getAnswerWavPath();

                }

            }

            if (!StringUtils.hasText(wavPath)) {

                var detailRule = dialogScriptPackRegistry.matchKeyword("索要详细方案", kbId);

                if (detailRule != null) {

                    DialogTrainingQa qa = dialogTrainingQaMapper.selectById(detailRule.id());

                    if (qa != null && StringUtils.hasText(qa.getAnswerWavPath())) {

                        wavPath = qa.getAnswerWavPath();

                        if (StringUtils.hasText(qa.getStandardAnswer())) {

                            fallback = qa.getStandardAnswer().trim();

                        }

                    }

                }

            }

        }

        if (!StringUtils.hasText(wavPath)) {

            DialogTrainingQa byText = dialogTrainingQaService.findByAnswerText(kbId, fallback);

            if (byText != null && StringUtils.hasText(byText.getAnswerWavPath())) {

                wavPath = byText.getAnswerWavPath();

            }

        }

        try {

            if (playRecordedLine(fsUuid, callRecordId, fallback, wavPath, kbId)

                    || (!isTrainingMode() && recordingOnlyPlaybackService.playCachedPhrase(fsUuid, fallback, kbId))) {

                DialogTranscriptLog.aiReply(callRecordId, fsUuid, fallback, "kb-teacher-fallback", false);

                result.setHandled(true);

                result.setReplyText(fallback);

                result.setModel("kb-teacher-fallback");

                result.setPlaybackWaitHandled(true);

                return result;

            }

        } catch (Exception e) {

            log.warn("[知识库录音] 产品老师兜底播放失败 uuid={}: {}", fsUuid, e.getMessage());

        }

        log.warn("[知识库录音] 产品老师兜底无录音 uuid={} text={}", fsUuid, fallback);

        PrerecordTurnResultDto recovered = tryResumeCurrentMainFlow(
                fsUuid, callRecordId, kbId, result, "kb-teacher-resume-main");
        if (recovered.isHandled()) {
            return recovered;
        }

        result.setHandled(false);

        result.setReplyText(fallback);

        result.setModel("kb-teacher-fallback-missed");

        result.setPlaybackWaitHandled(false);

        return result;

    }



    private PrerecordTurnResultDto playNoMatchFallback(String fsUuid, Integer callRecordId, int kbId,

                                                        PrerecordTurnResultDto result) {

        String fallback = dialogRagRetrievalService.noMatchFallbackText();

        try {

            if (playRecordedLine(fsUuid, callRecordId, fallback, null, kbId)

                    || (!isTrainingMode() && recordingOnlyPlaybackService.playCachedPhrase(fsUuid, fallback, kbId))) {

                DialogTranscriptLog.aiReply(callRecordId, fsUuid, fallback, "kb-no-match", false);

                result.setHandled(true);

                result.setReplyText(fallback);

                result.setModel("kb-no-match");

                result.setPlaybackWaitHandled(true);

                return result;

            }

        } catch (Exception e) {

            log.warn("[知识库录音] 无匹配兜底播放失败 uuid={}: {}", fsUuid, e.getMessage());

        }

        log.warn("[知识库录音] 无匹配兜底无录音 uuid={} text={}", fsUuid, fallback);

        result.setHandled(true);

        result.setReplyText(fallback);

        result.setModel("kb-no-match-silent");

        result.setPlaybackWaitHandled(true);

        return result;

    }



    private PrerecordTurnResultDto playRefuseAndEnd(String fsUuid, Integer callRecordId, int kbId,

                                                    PrerecordTurnResultDto result) {

        String reply = ForcedHangupRules.REFUSE_END_WORDS;

        try {

            if (playRecordedLine(fsUuid, callRecordId, reply, null, kbId)

                    || (!isTrainingMode() && (recordingOnlyPlaybackService.playCachedPhrase(fsUuid, reply, kbId)

                    || recordingOnlyPlaybackService.playEnding(fsUuid, callRecordId, reply)))) {

                DialogTranscriptLog.aiReply(callRecordId, fsUuid, reply, "kb-refuse", true);

                result.setHandled(true);

                result.setShouldHangup(true);

                result.setReplyText(reply);

                result.setModel("kb-refuse");

                result.setPlaybackWaitHandled(true);

                return result;

            }

        } catch (Exception e) {

            log.warn("[知识库录音] 拒绝结束播放失败 uuid={}: {}", fsUuid, e.getMessage());

        }

        result.setHandled(true);

        result.setShouldHangup(true);

        result.setReplyText(ForcedHangupRules.resolvePoliteEndWords(reply));

        result.setModel("kb-refuse-silent");

        result.setPlaybackWaitHandled(true);

        return result;

    }

}


