package com.aicall.service;

import com.aicall.entity.CallRecord;
import com.aicall.fs.FsOutboundSocketWriter;
import com.aicall.fs.FsSocketMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * FS Outbound Socket 8888：connect → sendmsg bridge → ESL 人机对话。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OutboundSocketDialogService {

    private final FreeSwitchEslService eslService;
    private final CallSessionService callSessionService;
    private final OutboundCallPendingService outboundCallPendingService;
    private final OutboundDialogLoopService outboundDialogLoopService;

    public void handle(Socket socket) {
        String uuid = null;
        try (socket) {
            socket.setSoTimeout(120_000);
            socket.setKeepAlive(true);
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            FsOutboundSocketWriter writer = new FsOutboundSocketWriter(socket);

            writer.connect();
            FsSocketMessage channelData = FsSocketMessage.read(in).mergeBodyAsHeaders();
            log.info("[8888] connect 后 Channel-Unique-ID={} Caller-Destination-Number={}",
                    channelData.channelVar("Channel-Unique-ID"),
                    channelData.channelVar("Caller-Destination-Number"));

            ChannelInfo info = resolveChannelInfo(channelData);
            uuid = info.uuid();
            String phone = info.phone();
            String gateway = info.gateway();
            log.info("[8888] 收到通道 uuid={} phone={} gateway={}", uuid, phone, gateway);

            if (!StringUtils.hasText(phone) || !StringUtils.hasText(uuid)) {
                log.warn("[8888] 缺少 uuid/phone，无法 bridge uuid={} phone={}", uuid, phone);
                return;
            }

            String bridgeArg = "{ignore_early_media=true}sofia/gateway/" + gateway + "/" + phone;
            log.info("[8888] sendmsg bridge {}", bridgeArg);
            writer.execute("bridge", bridgeArg, true);
            drainReply(in);

            if (!waitForAnswer(uuid, 45)) {
                log.warn("[8888] 未接通 uuid={} phone={}", uuid, phone);
                return;
            }

            CallSessionService.StartReq start = new CallSessionService.StartReq();
            start.setFsUuid(uuid);
            start.setTenantId(info.tenantId());
            start.setLineId(info.lineId());
            start.setTaskId(info.taskId());
            start.setCustomerId(info.customerId());
            start.setCustomerPhone(phone);
            start.setSkipOpeningVoice(true);
            CallRecord record = callSessionService.startSession(start);
            log.info("[8888] 会话已建立 callRecordId={} uuid={}", record.getId(), uuid);

            outboundDialogLoopService.run(uuid, record.getId());
            keepSocketAliveUntilCallEnds(in, uuid);
        } catch (Exception e) {
            log.warn("[8888] 处理异常 uuid={}: {}", uuid, e.getMessage(), e);
        }
    }

    private void drainReply(BufferedReader in) {
        try {
            if (in.ready()) {
                FsSocketMessage.read(in);
            }
        } catch (Exception ignored) {
        }
    }

    private void keepSocketAliveUntilCallEnds(BufferedReader in, String uuid) throws Exception {
        while (eslService.uuidExists(uuid)) {
            drainReply(in);
            Thread.sleep(1000L);
        }
    }

    private ChannelInfo resolveChannelInfo(FsSocketMessage channelData) {
        String uuid = firstChannelVar(channelData,
                "Channel-Unique-ID", "Caller-Unique-ID", "Unique-ID", "variable_uuid");
        String phone = firstChannelVar(channelData,
                "Caller-Destination-Number", "Channel-Destination-Number", "destination_number", "ai_callee");
        String gateway = firstChannelVar(channelData, "ai_gateway", "sip_gateway_name");

        OutboundCallPendingService.PendingCall pending = outboundCallPendingService.getLatest();
        if (!StringUtils.hasText(uuid) && pending != null) {
            uuid = pending.getFsUuid();
        }
        if (!StringUtils.hasText(phone) && pending != null) {
            phone = pending.getCallee();
        }
        if (pending != null) {
            log.info("[8888] pending 补充 uuid={} phone={}", uuid, phone);
        }
        if (!StringUtils.hasText(gateway)) {
            gateway = "343543656";
        }
        Integer tenantId = parseIntHeader(channelData, "ai_tenant_id");
        Integer lineId = parseIntHeader(channelData, "ai_line_id");
        Integer taskId = parseIntHeader(channelData, "ai_task_id");
        Integer customerId = parseIntHeader(channelData, "ai_customer_id");
        if (pending != null) {
            if (tenantId == null) {
                tenantId = pending.getTenantId();
            }
            if (lineId == null) {
                lineId = pending.getLineId();
            }
            if (taskId == null) {
                taskId = pending.getTaskId();
            }
            if (customerId == null) {
                customerId = pending.getCustomerId();
            }
        }
        return new ChannelInfo(uuid, phone, gateway, tenantId, lineId, taskId, customerId);
    }

    private String firstChannelVar(FsSocketMessage msg, String... names) {
        for (String name : names) {
            String v = msg.channelVar(name);
            if (StringUtils.hasText(v)) {
                return v.trim();
            }
        }
        return "";
    }

    private Integer parseIntHeader(FsSocketMessage msg, String name) {
        String v = msg.channelVar(name);
        if (!StringUtils.hasText(v)) {
            return null;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private boolean waitForAnswer(String uuid, int maxSec) throws Exception {
        for (int i = 0; i < maxSec; i++) {
            if (eslService.isChannelAnswered(uuid)) {
                return true;
            }
            if (!eslService.uuidExists(uuid)) {
                return false;
            }
            Thread.sleep(1000L);
        }
        return eslService.isChannelAnswered(uuid);
    }

    private record ChannelInfo(String uuid, String phone, String gateway,
                               Integer tenantId, Integer lineId, Integer taskId, Integer customerId) {
    }
}
