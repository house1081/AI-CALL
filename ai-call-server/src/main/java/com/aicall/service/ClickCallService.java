package com.aicall.service;

import com.aicall.common.BizException;
import com.aicall.common.CallMode;
import com.aicall.common.CallStatus;
import com.aicall.common.HangupType;
import com.aicall.config.FreeSwitchProperties;
import com.aicall.entity.CallRecord;
import com.aicall.entity.Customer;
import com.aicall.entity.Line;
import com.aicall.entity.Tenant;
import com.aicall.entity.TenantAgent;
import com.aicall.mapper.CallRecordMapper;
import com.aicall.mapper.CustomerMapper;
import com.aicall.mapper.TenantMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * 租户点呼：先呼坐席接通，再呼客户，uuid_bridge。不进入 AI 对话。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClickCallService {

    private static final Pattern PHONE = Pattern.compile("^1\\d{10}$");
    private static final int AGENT_RING_SEC = 60;
    private static final int CUSTOMER_RING_SEC = 60;

    private final TenantAgentService tenantAgentService;
    private final AgentRoutingService agentRoutingService;
    private final RiskControlService riskControlService;
    private final BillingService billingService;
    private final FreeSwitchProperties freeSwitchProperties;
    private final FreeSwitchEslService eslService;
    private final LineOutboundRouteService lineOutboundRouteService;
    private final CallRecordMapper callRecordMapper;
    private final CustomerMapper customerMapper;
    private final TenantMapper tenantMapper;
    private final CallSessionService callSessionService;

    private final ExecutorService dialExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "click-call");
        t.setDaemon(true);
        return t;
    });

    public Map<String, Object> start(Integer tenantId, ClickCallRequest req) {
        if (tenantId == null) {
            throw new BizException("未登录");
        }
        if (req == null || req.getAgentId() == null) {
            throw new BizException("请选择坐席");
        }
        Tenant tenant = tenantMapper.selectById(tenantId);
        if (tenant == null || tenant.getStatus() == null || tenant.getStatus() != 1) {
            throw new BizException("商户不可用");
        }
        billingService.assertCanStartTask(tenant);
        if (!riskControlService.inCallWindow()) {
            throw new BizException("当前不在允许外呼时段");
        }
        if (riskControlService.tenantDailyLimitReached(tenant)) {
            throw new BizException("已达商户今日外呼上限");
        }

        TenantAgent agent = tenantAgentService.requireOwned(tenantId, req.getAgentId());
        if (agent.getStatus() == null || agent.getStatus() != 1) {
            throw new BizException("坐席已停用");
        }
        if (TenantAgentService.PRESENCE_BUSY.equals(agent.getPresence())) {
            throw new BizException("坐席忙线中");
        }
        if (TenantAgentService.PRESENCE_OFFLINE.equals(agent.getPresence())) {
            throw new BizException("坐席未签入，请先设为在岗");
        }

        String phone = resolvePhone(tenantId, req);
        if (riskControlService.isGlobalBlacklisted(phone)) {
            throw new BizException("号码在全局黑名单中");
        }
        Customer customer = null;
        if (req.getCustomerId() != null) {
            customer = customerMapper.selectById(req.getCustomerId());
            if (customer == null || !tenantId.equals(customer.getTenantId())) {
                throw new BizException("客户不存在");
            }
            if (customer.getIsBlack() != null && customer.getIsBlack() == 1) {
                throw new BizException("客户在黑名单中");
            }
        }

        Integer lineId = req.getLineId();
        if (lineId == null) {
            if (agent.getLineId() != null) {
                lineId = agent.getLineId();
            } else {
                Line line = agentRoutingService.firstEnabledLine();
                if (line == null) {
                    throw new BizException("无可用线路");
                }
                lineId = line.getId();
            }
        }
        LineOutboundRouteService.LineRoute route = lineOutboundRouteService.requireRoute(lineId);
        if (riskControlService.isLineBlocked(lineId)) {
            throw new BizException("线路临时拦截中");
        }

        String agentDest = agentRoutingService.resolveFsDialDest(agent, lineId);
        if (!StringUtils.hasText(agentDest)) {
            throw new BizException("坐席拨号目标无效");
        }
        if (!freeSwitchProperties.isEnabled()) {
            throw new BizException("FreeSWITCH 未启用");
        }
        if (!eslService.reachable()) {
            throw new BizException("ESL 不可用，无法点呼");
        }

        CallRecord record = new CallRecord();
        record.setTenantId(tenantId);
        record.setLineId(lineId);
        record.setCustomerId(customer != null ? customer.getId() : null);
        record.setCustomerPhone(phone);
        record.setAgentId(agent.getId());
        record.setCallMode(CallMode.CLICK_CALL);
        record.setCallStatus(CallStatus.IN_PROGRESS);
        record.setCallDuration(0);
        record.setBilledMinutes(0);
        record.setPrepaidAmount(BigDecimal.ZERO);
        record.setDeductAmount(BigDecimal.ZERO);
        record.setCostAmount(BigDecimal.ZERO);
        record.setProfit(BigDecimal.ZERO);
        record.setProfitAbnormal(0);
        record.setCallTime(LocalDateTime.now());
        record.setLevel("D");
        callRecordMapper.insert(record);

        agentRoutingService.markBusy(agent.getId());
        riskControlService.incrementTenantDailyCallCount(tenantId);

        Integer recordId = record.getId();
        Integer agentId = agent.getId();
        final Integer dialLineId = lineId;

        // 同步呼叫坐席：失败立刻返回前端，避免「已发起但完全没动静」
        String agentUuid = UUID.randomUUID().toString();
        log.info("[点呼] 同步呼坐席 recordId={} agentId={} destType={} dest={} uuid={}",
                recordId, agentId, agent.getDestType(), agentDest, agentUuid);
        String callerId = route.getGatewayName();
        String agentErr = dialAgentOrError(agentUuid, agentDest, callerId);
        if (agentErr != null) {
            failRecord(recordId, CallStatus.DIAL_FAIL, trimReason("呼坐席失败:" + agentErr), agentId);
            throw new BizException(buildAgentFailMessage(agent, agentDest, agentErr));
        }
        eslService.ensureOutboundMediaReady(agentUuid);
        playWaitTone(agentUuid);

        dialExecutor.execute(() -> continueAfterAgent(
                recordId, agentId, agentUuid, phone, route, System.currentTimeMillis()));

        Map<String, Object> out = new HashMap<>();
        out.put("recordId", recordId);
        out.put("status", "agent_answered");
        out.put("agentId", agentId);
        out.put("agentDest", agentDest);
        out.put("customerPhone", phone);
        out.put("message", "坐席已接通，正在呼叫客户…");
        return out;
    }

    private static String buildAgentFailMessage(TenantAgent agent, String agentDest, String err) {
        if (err != null && (err.contains("USER_NOT_REGISTERED") || err.contains("分机未在FS注册"))) {
            return "分机 " + agentDest + " 未在 FreeSWITCH 注册。"
                    + "请用软电话（如 MicroSIP/Linphone）注册到 FS 的 SIP（常见 5060），"
                    + "用户名与坐席分机一致（如 1001），注册成功后再点呼。";
        }
        String tip;
        if (TenantAgentService.DEST_EXT.equalsIgnoreCase(agent.getDestType())) {
            tip = "请确认软电话已用该分机注册到 FreeSWITCH，并在坐席管理设为在岗。";
        } else {
            tip = "当前为手机坐席，系统经线路外呼 " + agentDest
                    + "。请确认该手机能接到此线路来电，或改用已注册的分机坐席。";
        }
        return "呼坐席失败：" + err + "。" + tip;
    }

    /** @return null=成功，否则为错误摘要 */
    private String dialAgentOrError(String agentUuid, String agentDest, String callerId) {
        List<String> candidates = agentDestCandidates(agentDest);
        String lastErr = "未知错误";
        boolean notRegistered = false;
        for (String dest : candidates) {
            // 分机未注册时不必再试 loopback（只会变成 DESTINATION_OUT_OF_ORDER）
            if (notRegistered && dest.startsWith("loopback/")) {
                continue;
            }
            log.info("[点呼] 尝试呼坐席 dest={}", dest);
            // 网关外呼必须用无逗号的 codec，并带主叫；分机可不带 callerId
            String cid = dest.contains("sofia/gateway/") ? callerId : null;
            String err = originateSyncError(agentUuid, dest, AGENT_RING_SEC, "&park()", cid);
            if (err == null) {
                return null;
            }
            lastErr = err;
            if (err.contains("USER_NOT_REGISTERED") || err.contains("分机未在FS注册")) {
                notRegistered = true;
            }
            if (eslService.uuidExists(agentUuid)) {
                return null;
            }
        }
        if (notRegistered) {
            return "分机未在FS注册(USER_NOT_REGISTERED)";
        }
        return lastErr;
    }

    private String resolvePhone(Integer tenantId, ClickCallRequest req) {
        if (StringUtils.hasText(req.getPhone())) {
            String p = req.getPhone().trim();
            if (!PHONE.matcher(p).matches()) {
                throw new BizException("手机号格式不正确");
            }
            return p;
        }
        if (req.getCustomerId() == null) {
            throw new BizException("请指定客户或手机号");
        }
        Customer c = customerMapper.selectById(req.getCustomerId());
        if (c == null || !tenantId.equals(c.getTenantId())) {
            throw new BizException("客户不存在");
        }
        if (!PHONE.matcher(c.getPhone()).matches()) {
            throw new BizException("客户手机号无效");
        }
        return c.getPhone();
    }

    private String lastOriginateError = "";

    /** 坐席已接通后：呼客户并桥接 */
    private void continueAfterAgent(Integer recordId, Integer agentId, String agentUuid,
                                    String customerPhone, LineOutboundRouteService.LineRoute route,
                                    long startMs) {
        String customerUuid = null;
        try {
            if (!eslService.uuidExists(agentUuid)) {
                failRecord(recordId, CallStatus.NO_ANSWER, "坐席已挂机", agentId);
                return;
            }
            log.info("[点呼] 坐席已接，开始呼客户 recordId={} agentUuid={} phone={}",
                    recordId, agentUuid, customerPhone);

            customerUuid = UUID.randomUUID().toString();
            String customerDest = "sofia/gateway/" + route.getGatewayName() + "/" + customerPhone;
            if (!originateSync(customerUuid, customerDest, CUSTOMER_RING_SEC, "&park()", route.getGatewayName())) {
                eslService.hangupChannel(agentUuid, "customer-originate-fail");
                String reason = StringUtils.hasText(lastOriginateError)
                        ? ("呼客户失败:" + lastOriginateError)
                        : "呼客户失败";
                failRecord(recordId, CallStatus.DIAL_FAIL, trimReason(reason), agentId);
                return;
            }
            if (!eslService.uuidExists(customerUuid) && !waitAnswered(customerUuid, 5, "客户")) {
                eslService.hangupChannel(agentUuid, "customer-no-channel");
                failRecord(recordId, CallStatus.NO_ANSWER, "客户通道未建立", agentId);
                return;
            }
            if (!eslService.uuidExists(agentUuid)) {
                eslService.hangupChannel(customerUuid, "agent-gone");
                failRecord(recordId, CallStatus.NO_ANSWER, "坐席已挂机", agentId);
                return;
            }
            eslService.ensureOutboundMediaReady(customerUuid);

            if (!bridgeLegs(agentUuid, customerUuid)) {
                eslService.hangupChannel(agentUuid, "bridge-fail");
                eslService.hangupChannel(customerUuid, "bridge-fail");
                failRecord(recordId, CallStatus.DIAL_FAIL, "桥接失败", agentId);
                return;
            }
            log.info("[点呼] 已桥接 recordId={} agent={} customer={}", recordId, agentUuid, customerUuid);
            unmuteBoth(agentUuid, customerUuid);
            watchUntilHangup(agentUuid, customerUuid, recordId, agentId, startMs);
        } catch (Exception e) {
            log.error("[点呼] 异常 recordId={}: {}", recordId, e.getMessage(), e);
            if (agentUuid != null) {
                eslService.hangupChannel(agentUuid, "click-call-error");
            }
            if (customerUuid != null) {
                eslService.hangupChannel(customerUuid, "click-call-error");
            }
            failRecord(recordId, CallStatus.DIAL_FAIL, "点呼异常:" + e.getMessage(), agentId);
        }
    }

    private static List<String> agentDestCandidates(String primaryDest) {
        List<String> list = new ArrayList<>();
        if (!StringUtils.hasText(primaryDest)) {
            return list;
        }
        String d = primaryDest.trim();
        list.add(d);
        if (d.startsWith("user/")) {
            String ext = d.substring("user/".length()).trim();
            if (StringUtils.hasText(ext)) {
                addUnique(list, "sofia/internal/" + ext);
                addUnique(list, "loopback/" + ext + "/default");
            }
        } else if (d.matches("^\\d{2,8}$")) {
            addUnique(list, "user/" + d);
            addUnique(list, "sofia/internal/" + d);
            addUnique(list, "loopback/" + d + "/default");
        }
        return list;
    }

    private static void addUnique(List<String> list, String v) {
        if (StringUtils.hasText(v) && !list.contains(v)) {
            list.add(v);
        }
    }

    /**
     * 同步 originate：阻塞至接通或失败。
     * 注意：channel vars 以逗号分隔，absolute_codec_string 不能再含逗号，否则会拆坏拨号串。
     * @return null 成功，否则错误摘要
     */
    private String originateSyncError(String uuid, String dest, int timeoutSec, String app, String callerId) {
        lastOriginateError = "";
        String vars = "origination_uuid=" + uuid
                + ",originate_timeout=" + timeoutSec
                + ",ignore_early_media=true"
                + ",absolute_codec_string=PCMU@8000h@20i"
                + ",bypass_media=false"
                + ",proxy_media=false"
                + ",rtp_autoflush_during_bridge=true"
                + ",hangup_after_bridge=true";
        if (StringUtils.hasText(callerId)) {
            vars += ",origination_caller_id_number=" + callerId.trim().replace(",", "");
        }
        String dial = "{" + vars + "}" + dest + " " + app;
        log.info("[点呼] ESL api originate uuid={} dest={} app={} callerId={}", uuid, dest, app, callerId);
        FreeSwitchEslService.EslResponse resp = eslService.api("originate " + dial);
        String reply = firstNonEmpty(resp.getReplyText(), resp.getBody());
        log.info("[点呼] originate reply={}", reply);
        if (reply != null && reply.contains("+OK")) {
            lastOriginateError = "";
            return null;
        }
        lastOriginateError = summarizeOriginateError(reply);
        log.warn("[点呼] originate 失败 dest={} err={}", dest, lastOriginateError);
        return lastOriginateError;
    }

    private boolean originateSync(String uuid, String dest, int timeoutSec, String app, String callerId) {
        return originateSyncError(uuid, dest, timeoutSec, app, callerId) == null;
    }

    private static String summarizeOriginateError(String reply) {
        if (!StringUtils.hasText(reply)) {
            return "无响应";
        }
        String r = reply.replace('\n', ' ').trim();
        int err = r.indexOf("-ERR");
        String code = err >= 0 ? r.substring(err + 4).trim() : r;
        if (code.length() > 48) {
            code = code.substring(0, 48);
        }
        String upper = code.toUpperCase();
        if (upper.contains("USER_NOT_REGISTERED")) {
            return "分机未在FS注册(USER_NOT_REGISTERED)";
        }
        if (upper.contains("NO_USER_RESPONSE")) {
            return "坐席无应答/线路无响应(NO_USER_RESPONSE)";
        }
        if (upper.contains("NO_ANSWER") || upper.contains("NO_USER")) {
            return "坐席未接听(" + code + ")";
        }
        if (upper.contains("SUBSCRIBER_ABSENT")) {
            return "号码空号或不在服务区";
        }
        if (upper.contains("CALL_REJECTED")) {
            return "坐席拒接";
        }
        if (upper.contains("NORMAL_TEMPORARY_FAILURE") || upper.contains("GATEWAY")) {
            return "网关临时失败(" + code + ")";
        }
        return code;
    }

    private static String firstNonEmpty(String a, String b) {
        if (StringUtils.hasText(a)) {
            return a;
        }
        return b;
    }

    private static String trimReason(String reason) {
        if (reason == null) {
            return null;
        }
        return reason.length() > 60 ? reason.substring(0, 60) : reason;
    }

    private void playWaitTone(String agentUuid) {
        try {
            eslService.api("uuid_broadcast " + agentUuid
                    + " tone_stream://%(200,4000,425,480);loops=20 aleg");
        } catch (Exception e) {
            log.debug("[点呼] 等待音播放跳过: {}", e.getMessage());
        }
    }

    private boolean bridgeLegs(String agentUuid, String customerUuid) {
        try {
            eslService.api("uuid_break " + agentUuid);
            sleepQuiet(150L);
            FreeSwitchEslService.EslResponse resp = eslService.api(
                    "uuid_bridge " + agentUuid + " " + customerUuid);
            String reply = firstNonEmpty(resp.getReplyText(), resp.getBody());
            log.info("[点呼] uuid_bridge reply={}", reply);
            if (resp.isOk() || (reply != null && reply.contains("+OK"))) {
                return true;
            }
            FreeSwitchEslService.EslResponse resp2 = eslService.api(
                    "uuid_bridge " + customerUuid + " " + agentUuid);
            String reply2 = firstNonEmpty(resp2.getReplyText(), resp2.getBody());
            log.info("[点呼] uuid_bridge 反向 reply={}", reply2);
            return resp2.isOk() || (reply2 != null && reply2.contains("+OK"));
        } catch (Exception e) {
            log.warn("[点呼] uuid_bridge 异常: {}", e.getMessage());
            return false;
        }
    }

    private void unmuteBoth(String agentUuid, String customerUuid) {
        for (String uuid : new String[]{agentUuid, customerUuid}) {
            if (!StringUtils.hasText(uuid) || !eslService.uuidExists(uuid)) {
                continue;
            }
            try {
                eslService.ensureOutboundMediaReady(uuid);
                eslService.api("uuid_audio " + uuid + " start read write");
                eslService.api("uuid_setvar " + uuid + " mute_read false");
                eslService.api("uuid_setvar " + uuid + " mute_write false");
            } catch (Exception e) {
                log.warn("[点呼] unmute 失败 uuid={}: {}", uuid, e.getMessage());
            }
        }
    }

    /**
     * 等待通道真实摘机。uuid 尚未创建时继续等，出现过后再消失则判失败。
     */
    private boolean waitAnswered(String uuid, int maxSec, String role) {
        boolean seen = false;
        for (int i = 0; i < maxSec; i++) {
            sleepQuiet(1000L);
            boolean exists = eslService.uuidExists(uuid);
            if (exists) {
                seen = true;
                if (eslService.isChannelAnswered(uuid)) {
                    log.info("[点呼] {}已接通 uuid={} after={}s callstate={} answer_state={}",
                            role, uuid, i + 1,
                            safeVar(uuid, "callstate"),
                            safeVar(uuid, "answer_state"));
                    return true;
                }
                if (i > 0 && i % 5 == 0) {
                    log.info("[点呼] {}等待接通中 uuid={} t={}s callstate={} disp={} answer_state={}",
                            role, uuid, i,
                            safeVar(uuid, "callstate"),
                            safeVar(uuid, "endpoint_disposition"),
                            safeVar(uuid, "answer_state"));
                }
                continue;
            }
            if (seen) {
                log.warn("[点呼] {}通道已消失 uuid={} after={}s disp={}",
                        role, uuid, i + 1, safeVar(uuid, "originate_disposition"));
                return false;
            }
            if (i >= 8) {
                log.warn("[点呼] {}通道一直未创建 uuid={} after={}s", role, uuid, i + 1);
                return false;
            }
        }
        log.warn("[点呼] {}等待接通超时 uuid={} seen={} disp={}",
                role, uuid, seen, safeVar(uuid, "endpoint_disposition"));
        return false;
    }

    private String diagnoseNoAnswer(String uuid, String role) {
        String disp = safeVar(uuid, "originate_disposition");
        String hangup = safeVar(uuid, "hangup_cause");
        String tip = role + "未接听";
        if (StringUtils.hasText(disp) && !"_undef_".equalsIgnoreCase(disp)) {
            tip = tip + "(" + disp + ")";
        } else if (StringUtils.hasText(hangup) && !"_undef_".equalsIgnoreCase(hangup)) {
            tip = tip + "(" + hangup + ")";
        }
        return tip.length() > 60 ? tip.substring(0, 60) : tip;
    }

    private String safeVar(String uuid, String name) {
        try {
            if (!StringUtils.hasText(uuid) || !eslService.uuidExists(uuid)) {
                return "";
            }
            String v = eslService.uuidGetVar(uuid, name);
            return v != null ? v : "";
        } catch (Exception e) {
            return "";
        }
    }

    private void watchUntilHangup(String agentUuid, String customerUuid, Integer recordId,
                                  Integer agentId, long startMs) {
        int maxSec = Math.max(300, freeSwitchProperties.getOriginateTimeoutSec() + 600);
        for (int i = 0; i < maxSec; i++) {
            sleepQuiet(1000L);
            boolean agentUp = eslService.uuidExists(agentUuid);
            boolean custUp = StringUtils.hasText(customerUuid) && eslService.uuidExists(customerUuid);
            if (agentUp && custUp) {
                continue;
            }
            log.info("[点呼] 检测到挂断 recordId={} agentUp={} custUp={} after={}s",
                    recordId, agentUp, custUp, i + 1);
            if (agentUp) {
                eslService.hangupChannel(agentUuid, "peer-hangup");
            }
            if (custUp) {
                eslService.hangupChannel(customerUuid, "peer-hangup");
            }
            finishConnected(recordId, agentId, startMs);
            return;
        }
        eslService.hangupChannel(agentUuid, "click-call-timeout");
        eslService.hangupChannel(customerUuid, "click-call-timeout");
        finishConnected(recordId, agentId, startMs);
    }

    private void finishConnected(Integer recordId, Integer agentId, long startMs) {
        try {
            int duration = (int) Math.max(1, (System.currentTimeMillis() - startMs) / 1000);
            CallSessionService.EndReq end = new CallSessionService.EndReq();
            end.setCallRecordId(recordId);
            end.setCallDuration(duration);
            end.setCallStatus(CallStatus.CONNECTED);
            end.setHangupType(HangupType.NORMAL);
            end.setLevel("D");
            callSessionService.endSession(end);
        } catch (Exception e) {
            log.warn("[点呼] 结算失败 recordId={}: {}", recordId, e.getMessage());
            CallRecord upd = new CallRecord();
            upd.setId(recordId);
            upd.setCallStatus(CallStatus.CONNECTED);
            upd.setExitReason("click_call_end");
            callRecordMapper.updateById(upd);
        } finally {
            agentRoutingService.releaseAfterCall(agentId);
        }
    }

    private void failRecord(Integer recordId, int status, String reason, Integer agentId) {
        try {
            CallRecord upd = new CallRecord();
            upd.setId(recordId);
            upd.setCallStatus(status);
            upd.setCallDuration(0);
            upd.setHangupType(HangupType.NO_RESPONSE);
            upd.setExitReason(reason != null && reason.length() > 60 ? reason.substring(0, 60) : reason);
            callRecordMapper.updateById(upd);
            log.warn("[点呼] 失败 recordId={} status={} reason={}", recordId, status, reason);
        } finally {
            agentRoutingService.releaseAfterCall(agentId);
        }
    }

    private static void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Data
    public static class ClickCallRequest {
        private Integer customerId;
        private String phone;
        private Integer agentId;
        private Integer lineId;
    }
}
