package com.aicall.service;

import com.aicall.common.CallStatus;
import com.aicall.config.FreeSwitchProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 无人接听（振铃超时/未摘机）结算并终止外呼任务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OutboundNoAnswerService {

    private final CallSessionService callSessionService;
    private final OutboundCallWaitService outboundCallWaitService;
    private final FreeSwitchEslService freeSwitchEslService;
    private final FreeSwitchProperties freeSwitchProperties;
    private final OutboundCallPendingService outboundCallPendingService;

    public void onNoAnswer(String fsUuid, Integer taskId, String phone, String reason) {
        log.info("[外呼规则] 无人接听 {} uuid={} phone={} taskId={}", reason, fsUuid, phone, taskId);
        if (StringUtils.hasText(fsUuid)) {
            try {
                if (freeSwitchProperties.isEnabled()) {
                    freeSwitchEslService.api("uuid_kill " + fsUuid);
                }
            } catch (Exception ignored) {
            }
            try {
                CallSessionService.EndReq end = new CallSessionService.EndReq();
                end.setFsUuid(fsUuid);
                end.setCallStatus(CallStatus.NO_ANSWER);
                end.setCallDuration(0);
                callSessionService.endSession(end);
            } catch (Exception e) {
                log.debug("[外呼规则] 无人接听结算 uuid={}: {}", fsUuid, e.getMessage());
            }
            outboundCallPendingService.remove(fsUuid);
        }
        if (taskId != null) {
            outboundCallWaitService.complete(fsUuid, taskId);
        }
    }
}
