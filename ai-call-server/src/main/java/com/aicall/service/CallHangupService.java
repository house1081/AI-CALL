package com.aicall.service;

import com.aicall.common.CallStatus;
import com.aicall.common.ForcedHangupRules;
import com.aicall.common.HangupType;
import com.aicall.config.FreeSwitchProperties;
import com.aicall.dto.HangupDecision;
import com.aicall.entity.CallRecord;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class CallHangupService {

    private final CallSessionService callSessionService;
    private final ForcedHangupService forcedHangupService;
    private final FreeSwitchProperties freeSwitchProperties;
    private final FreeSwitchDialService freeSwitchDialService;
    private final TtsFailureRecoveryService ttsFailureRecoveryService;

    public HangupDecision tick(Integer callRecordId) {
        return forcedHangupService.checkDurationOnly(callRecordId, null);
    }

    @Transactional
    public Map<String, Object> executeHangup(HangupReq req) {
        CallRecord record = callSessionService.getSession(req.getCallRecordId());
        int duration = req.getCallDuration() != null
                ? req.getCallDuration()
                : forcedHangupService.elapsedSeconds(req.getCallRecordId(), null);

        String hangupType = StringUtils.hasText(req.getHangupType())
                ? req.getHangupType()
                : HangupType.FORCE_DURATION;

        CallSessionService.EndReq end = new CallSessionService.EndReq();
        end.setCallRecordId(req.getCallRecordId());
        end.setCallDuration(duration);
        end.setCallStatus(CallStatus.CONNECTED);
        end.setHangupType(hangupType);
        end.setRecordUrl(req.getRecordUrl());
        end.setDialogText(req.getDialogText());
        end.setCustomerNeed(req.getCustomerNeed());
        end.setCustomerPain(req.getCustomerPain());
        end.setBudget(req.getBudget());
        end.setNextTime(req.getNextTime());
        end.setLevel(req.getLevel());
        callSessionService.endSession(end);

        String fsUuid = StringUtils.hasText(req.getFsUuid())
                ? req.getFsUuid()
                : forcedHangupService.getFsUuid(req.getCallRecordId(), null);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("callRecordId", req.getCallRecordId());
        data.put("hangupType", hangupType);
        data.put("playText", ForcedHangupRules.END_WORDS);
        data.put("callDuration", duration);
        data.put("fsUuid", fsUuid);
        data.put("eslEndpoint", freeSwitchProperties.eslEndpoint());
        if (StringUtils.hasText(fsUuid)) {
            if (duration > 0) {
                ttsFailureRecoveryService.playEndingThenHangup(
                        fsUuid, req.getCallRecordId(), ForcedHangupRules.END_WORDS, hangupType);
            } else {
                freeSwitchDialService.hangup(fsUuid);
            }
            data.put("eslCommand", "uuid_kill " + fsUuid);
        }
        log.info("强制挂断 callRecordId={} type={} duration={}s", req.getCallRecordId(), hangupType, duration);
        return data;
    }

    @Data
    public static class HangupReq {
        private Integer callRecordId;
        private String hangupType;
        private Integer callDuration;
        private String fsUuid;
        private String recordUrl;
        private String dialogText;
        private String customerNeed;
        private String customerPain;
        private String budget;
        private String nextTime;
        private String level;
    }
}
