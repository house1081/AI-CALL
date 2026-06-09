package com.aicall.service;

import com.aicall.common.BizException;
import com.aicall.config.FreeSwitchProperties;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.UUID;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class FreeSwitchDialService {

    private static final Pattern PHONE = Pattern.compile("^1\\d{10}$");

    private final FreeSwitchProperties props;
    private final FreeSwitchEslService eslService;
    private final OutboundCallPendingService pendingService;
    private final LineOutboundRouteService lineOutboundRouteService;
    private final OutboundAnswerVoiceService outboundAnswerVoiceService;
    private final OutboundCallWaitService outboundCallWaitService;

    public DialResult originate(DialRequest req) {
        validate(req);
        String uuid = UUID.randomUUID().toString();
        LineOutboundRouteService.LineRoute route = lineOutboundRouteService.requireRoute(req.getLineId());

        if (!props.isEnabled()) {
            log.info("[FS模拟] line={} gateway={} sip={} phone={} uuid={}",
                    route.getLineId(), route.getGatewayName(), route.getSipAddress(), req.getCallee(), uuid);
            DialResult r = new DialResult();
            r.setSuccess(true);
            r.setFsUuid(uuid);
            r.setMode("simulate");
            r.setGateway(route.getGatewayName());
            r.setSipAddress(route.getSipAddress());
            r.setHint("freeswitch.enabled=false；启用后将使用线路「" + route.getGatewayName() + "」真实外呼");
            return r;
        }

        if (!eslService.reachable()) {
            DialResult r = new DialResult();
            r.setSuccess(false);
            r.setFsUuid(uuid);
            r.setMode("esl-error");
            r.setGateway(route.getGatewayName());
            r.setSipAddress(route.getSipAddress());
            String detail = eslService.getLastError();
            r.setHint(StringUtils.hasText(detail)
                    ? detail
                    : "无法连接 ESL " + props.eslEndpoint());
            return r;
        }

        pendingService.register(uuid, req);
        LineOutboundRouteService.DialVars vars = new LineOutboundRouteService.DialVars();
        vars.setTenantId(req.getTenantId());
        vars.setTaskId(req.getTaskId());
        vars.setCustomerId(req.getCustomerId());
        vars.setCallee(req.getCallee());
        vars.setCallerId(req.getCallerId());

        // 生成拨号串
        String dialString = lineOutboundRouteService.buildOriginateDialString(uuid, route, vars);

        // ====================== 最终回声消除 + 全双工插话 ======================
        String echoParams = ",enable_ec=true,ec_delay=60,ec_suppression=3,tts_say_while_listen=true,rtp_enable_vad=false";
        dialString = dialString.replace("}", echoParams + "}");
        // ====================================================================

        log.info("ESL originate lineId={} gateway={} sip={} phone={} dial={}",
                route.getLineId(), route.getGatewayName(), route.getSipAddress(), req.getCallee(), dialString);

        FreeSwitchEslService.EslResponse resp = eslService.bgapi("originate " + dialString);
        String originateReply = resp.getReplyText() != null ? resp.getReplyText() : resp.getBody();
        if (!resp.isOk()) {
            log.warn("ESL originate 拒绝 uuid={} phone={} reply={}", uuid, req.getCallee(), originateReply);
        } else {
            log.info("ESL originate 已提交 uuid={} phone={} reply={}", uuid, req.getCallee(), originateReply);
        }

        DialResult r = new DialResult();
        r.setFsUuid(uuid);
        r.setMode("esl");
        r.setGateway(route.getGatewayName());
        r.setSipAddress(route.getSipAddress());
        r.setEslReply(resp.getReplyText());
        if (resp.isOk()) {
            r.setSuccess(true);
            r.setHint("已用线路「" + route.getGatewayName() + "」(" + route.getSipAddress() + ") 下发外呼");
            outboundCallWaitService.register(uuid, req.getTaskId());
            if (!props.isSocketOutboundMode()) {
                outboundAnswerVoiceService.scheduleAfterOriginate(uuid, req);
            } else {
                r.setHint(r.getHint() + "；socket 模式：等待 FS 连接 8888 完成对话");
            }
        } else {
            pendingService.remove(uuid);
            r.setSuccess(false);
            r.setHint("ESL 外呼失败: " + resp.getReplyText() + "；请确认 FS 存在 gateway " + route.getGatewayName());
        }
        return r;
    }

    public DialResult hangup(String fsUuid) {
        DialResult r = new DialResult();
        r.setFsUuid(fsUuid);
        if (!props.isEnabled() || !StringUtils.hasText(fsUuid)) {
            r.setSuccess(true);
            r.setMode("simulate");
            return r;
        }
        boolean ok = eslService.hangupChannel(fsUuid, "dial-hangup");
        r.setSuccess(ok);
        r.setMode("esl");
        r.setEslReply(ok ? "+OK" : "-ERR");
        r.setHint(ok ? "+OK" : "uuid_kill failed");
        pendingService.remove(fsUuid);
        return r;
    }

    private void validate(DialRequest req) {
        if (req.getTenantId() == null || req.getLineId() == null) {
            throw new BizException("外呼参数不完整");
        }
        if (!StringUtils.hasText(req.getCallee()) || !PHONE.matcher(req.getCallee().trim()).matches()) {
            throw new BizException("被叫号码须为 11 位手机号");
        }
    }

    @Data
    public static class DialRequest {
        private Integer tenantId;
        private Integer lineId;
        private Integer taskId;
        private Integer customerId;
        private String callee;
        private String callerId;
        /** 本通最大振铃次数，null 则用全局配置 */
        private Integer maxRingCount;
    }

    @Data
    public static class DialResult {
        private boolean success;
        private String fsUuid;
        private String mode;
        private String hint;
        private String eslReply;
        private String gateway;
        private String sipAddress;
    }
}
