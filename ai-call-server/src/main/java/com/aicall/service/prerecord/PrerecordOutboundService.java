package com.aicall.service.prerecord;

import com.aicall.common.ForcedHangupRules;
import com.aicall.dto.PrerecordTurnResultDto;
import com.aicall.entity.PrerecordFaq;
import com.aicall.service.CallDialogPersistService;
import com.aicall.service.HumanTransferService;
import com.aicall.util.DialogTranscriptLog;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class PrerecordOutboundService {

    private final PrerecordFaqMatchService prerecordFaqMatchService;
    private final PrerecordPlaybackService prerecordPlaybackService;
    private final HumanTransferService humanTransferService;
    private final CallDialogPersistService callDialogPersistService;

    public PrerecordTurnResultDto handleTurn(String fsUuid, Integer callRecordId, String phone,
                                             String userText, boolean circuitOnly) {
        PrerecordTurnResultDto result = new PrerecordTurnResultDto();
        result.setHandled(false);
        if (!StringUtils.hasText(fsUuid) || callRecordId == null || !StringUtils.hasText(userText)) {
            return result;
        }
        if (ForcedHangupRules.containsRefuseKeyword(userText)) {
            return playRefuseAndEnd(fsUuid, callRecordId, result);
        }

        Optional<PrerecordFaq> hit = prerecordFaqMatchService.match(userText);
        if (hit.isPresent()) {
            return playFaqHit(fsUuid, callRecordId, hit.get(), result);
        }
        if (circuitOnly) {
            return triggerAdvisor(fsUuid, callRecordId, phone, userText, result, true);
        }
        return result;
    }

    private PrerecordTurnResultDto playFaqHit(String fsUuid, Integer callRecordId, PrerecordFaq faq,
                                              PrerecordTurnResultDto result) {
        try {
            prerecordFaqMatchService.incrementMatch(faq.getId());
            String reply = faq.getAnswerText();
            DialogTranscriptLog.aiReply(callRecordId, fsUuid, reply, "prerecord-faq", false);
            prerecordPlaybackService.playFaqAnswerSequence(fsUuid, faq);
            result.setHandled(true);
            result.setReplyText(reply);
            result.setModel("prerecord-faq");
            result.setPlaybackWaitHandled(true);
            log.info("[预录外呼] FAQ应答 uuid={} faqId={} category={}", fsUuid, faq.getId(), faq.getCategory());
            return result;
        } catch (Exception e) {
            log.warn("[预录外呼] FAQ播放失败 uuid={} faqId={}: {}", fsUuid, faq.getId(), e.getMessage());
            return result;
        }
    }

    private PrerecordTurnResultDto triggerAdvisor(String fsUuid, Integer callRecordId, String phone,
                                                  String userText, PrerecordTurnResultDto result,
                                                  boolean circuitOnly) {
        try {
            prerecordFaqMatchService.incrementTransfer(null);
            callDialogPersistService.appendSystem(callRecordId, "冷门问题，转专业信贷顾问");
            prerecordPlaybackService.playTransferSequence(fsUuid);
            boolean ok = humanTransferService.triggerAdvisorTransfer(fsUuid, callRecordId, phone,
                    "预录兜底-冷门问题: " + abbreviate(userText));
            result.setHandled(ok);
            result.setTransferred(ok);
            result.setModel(ok ? "prerecord-transfer" : "prerecord-transfer-fail");
            result.setReplyText("已为您转接专业信贷顾问");
            result.setPlaybackWaitHandled(true);
            if (!ok && circuitOnly) {
                log.warn("[预录外呼] 转顾问失败，回退 AI uuid={}", fsUuid);
                result.setHandled(false);
            }
            return result;
        } catch (Exception e) {
            log.warn("[预录外呼] 转顾问异常 uuid={}: {}", fsUuid, e.getMessage());
            result.setHandled(false);
            return result;
        }
    }

    private PrerecordTurnResultDto playRefuseAndEnd(String fsUuid, Integer callRecordId,
                                                    PrerecordTurnResultDto result) {
        try {
            String reply = "好的，理解您的想法，那就不多打扰了，祝您生活愉快。";
            DialogTranscriptLog.aiReply(callRecordId, fsUuid, reply, "prerecord-refuse", true);
            prerecordPlaybackService.playRefuseEnding(fsUuid);
            result.setHandled(true);
            result.setShouldHangup(true);
            result.setReplyText(reply);
            result.setModel("prerecord-refuse");
            result.setPlaybackWaitHandled(true);
            return result;
        } catch (Exception e) {
            log.warn("[预录外呼] 拒绝结束播放失败 uuid={}: {}", fsUuid, e.getMessage());
            return result;
        }
    }

    private static String abbreviate(String text) {
        String s = text.trim();
        return s.length() <= 32 ? s : s.substring(0, 32) + "…";
    }
}
