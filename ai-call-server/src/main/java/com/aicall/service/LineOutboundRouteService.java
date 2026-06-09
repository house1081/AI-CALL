package com.aicall.service;

import com.aicall.common.BizException;
import com.aicall.config.AiVoiceProperties;
import com.aicall.config.FreeSwitchProperties;
import com.aicall.entity.Line;
import com.aicall.mapper.LineMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 从线路表读取真实 SIP/网关配置，用于 FreeSWITCH originate
 */
@Service
@RequiredArgsConstructor
public class LineOutboundRouteService {

    private final LineMapper lineMapper;
    private final FreeSwitchProperties freeSwitchProperties;
    private final AiVoiceProperties aiVoiceProperties;

    public LineRoute requireRoute(Integer lineId) {
        Line line = lineMapper.selectById(lineId);
        if (line == null) {
            throw new BizException("线路不存在 id=" + lineId);
        }
        if (line.getStatus() == null || line.getStatus() != 1) {
            throw new BizException("线路「" + line.getSipAccount() + "」未启用");
        }
        if (!StringUtils.hasText(line.getSipAccount())) {
            throw new BizException("线路 id=" + lineId + " 未配置 SIP 账号（FreeSWITCH gateway 名）");
        }
        if (!StringUtils.hasText(line.getSipAddress()) || !line.getSipAddress().contains(":")) {
            throw new BizException("线路「" + line.getSipAccount() + "」SIP 地址须为 host:port，如 192.168.60.28:5060");
        }

        String[] hostPort = line.getSipAddress().trim().split(":", 2);
        LineRoute route = new LineRoute();
        route.setLineId(line.getId());
        route.setGatewayName(line.getSipAccount().trim());
        route.setSipHost(hostPort[0].trim());
        route.setSipPort(hostPort.length > 1 ? hostPort[1].trim() : "5060");
        route.setSipAddress(line.getSipAddress().trim());
        route.setSipPassword(line.getSipPassword() != null ? line.getSipPassword() : "");
        route.setCostPrice(line.getCostPrice());
        return route;
    }

    /**
     * 构建 ESL originate 拨号串：gateway 名 = 线路表 sip_account（与 FS sofia gateway 名一致）
     */
    public String buildOriginateDialString(String fsUuid, LineRoute route, DialVars vars) {
        String gateway = route.getGatewayName();
        if (!StringUtils.hasText(gateway)) {
            gateway = freeSwitchProperties.getGateway();
        }
        int ringSec = freeSwitchProperties.getOriginateTimeoutSec();
        int talkSec = channelTalkTimeoutSec();
        String channelVars = "origination_uuid=" + fsUuid
                + ",originate_timeout=" + ringSec
                + ",call_timeout=" + talkSec
                + ",session_timeout=" + talkSec
                + ",rtp_timeout_sec=" + talkSec
                + ",ignore_early_media=true"
                + ",rtp_enable_vad=false"
                + ",suppress_cng=true"
                + mediaPacketizationVars()
                + silenceVars()
                + ",ai_tenant_id=" + vars.getTenantId()
                + ",ai_line_id=" + route.getLineId()
                + ",ai_task_id=" + (vars.getTaskId() != null ? vars.getTaskId() : 0)
                + ",ai_customer_id=" + (vars.getCustomerId() != null ? vars.getCustomerId() : 0)
                + ",ai_callback_base=" + sanitizeVar(freeSwitchProperties.getCallbackBaseUrl())
                + ",ai_sip_address=" + sanitizeVar(route.getSipAddress())
                + ",ai_sip_host=" + sanitizeVar(route.getSipHost())
                + ",ai_sip_port=" + sanitizeVar(route.getSipPort())
                + ",ai_gateway=" + sanitizeVar(gateway);
        if (StringUtils.hasText(route.getSipPassword())) {
            channelVars += ",ai_sip_password=" + sanitizeVar(route.getSipPassword());
        }
        if (StringUtils.hasText(vars.getCallerId())) {
            channelVars += ",origination_caller_id_number=" + sanitizeVar(vars.getCallerId());
        }
        String callee = vars.getCallee().trim();
        channelVars += ",ai_callee=" + sanitizeVar(callee);
        String app = freeSwitchProperties.getOriginateApplication();
        if (!StringUtils.hasText(app)) {
            app = "&echo";
        }
        if (freeSwitchProperties.isSocketOutboundMode()) {
            // 须 loopback/号码/上下文，否则 FS 会把号码当成 channel type（CHAN_NOT_IMPLEMENTED）
            String ctx = freeSwitchProperties.getOutboundContext();
            return "{" + channelVars + "}loopback/" + callee + "/" + ctx + " " + app.trim();
        }
        return "{" + channelVars + "}sofia/gateway/" + gateway + "/" + callee + " " + app.trim();
    }

    /** 接通后允许通话时长，与 ai-voice.dialog-max-call-sec 对齐，避免 FS 60s 先挂断导致无声音 */
    private int channelTalkTimeoutSec() {
        int dialog = Math.max(60, aiVoiceProperties.getDialogMaxCallSec());
        return Math.max(freeSwitchProperties.getOriginateTimeoutSec(), dialog + 30);
    }

    private String mediaPacketizationVars() {
        int ptime = Math.max(10, Math.min(40, aiVoiceProperties.getRtpPacketizationMs()));
        return ",absolute_codec_string=PCMU@8000h@" + ptime + "i"
                + ",enable_ec=true,ec_delay=60,ec_suppression=3,tts_say_while_listen=true"
                + ",media_webrtc=false"
                + ",bypass_media=false"
                + ",rtp_autoflush_during_bridge=true";
    }

    private String silenceVars() {
        if (aiVoiceProperties.isDisableComfortNoise()) {
            return ",send_silence_when_idle=-1";
        }
        return ",send_silence_when_idle=400";
    }

    private String sanitizeVar(String v) {
        if (v == null) {
            return "";
        }
        return v.replace(",", "").replace("}", "").replace("{", "").trim();
    }

    @Data
    public static class LineRoute {
        private Integer lineId;
        /** 对应 FS sofia gateway 名称，来自 line.sip_account */
        private String gatewayName;
        private String sipHost;
        private String sipPort;
        private String sipAddress;
        private String sipPassword;
        private java.math.BigDecimal costPrice;
    }

    @Data
    public static class DialVars {
        private Integer tenantId;
        private Integer taskId;
        private Integer customerId;
        private String callee;
        private String callerId;
    }
}
