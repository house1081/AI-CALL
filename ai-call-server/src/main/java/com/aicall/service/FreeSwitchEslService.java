package com.aicall.service;

import com.aicall.common.BizException;
import com.aicall.config.FreeSwitchProperties;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * FreeSWITCH Event Socket (ESL) 最小客户端：auth + api/bgapi
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FreeSwitchEslService {

    private static final Pattern ANSWER_EPOCH_IN_DUMP =
            Pattern.compile("(?im)(?:variable_)?answer_epoch:\\s*([1-9]\\d*)");

    private final FreeSwitchProperties props;
    private volatile String lastError;

    public EslResponse api(String command) {
        return execute("api " + command.trim());
    }

    public EslResponse bgapi(String command) {
        return execute("bgapi " + command.trim());
    }

    public boolean uuidExists(String uuid) {
        if (!StringUtils.hasText(uuid)) {
            return false;
        }
        try {
            EslResponse r = api("uuid_exists " + uuid.trim());
            String body = r.getBody() != null ? r.getBody() : "";
            return body.contains("true");
        } catch (Exception e) {
            return false;
        }
    }

    /** 中断通话并释放 FS 通道；uuid 已不存在则跳过 */
    public boolean hangupChannel(String uuid, String reason) {
        if (!StringUtils.hasText(uuid) || !uuidExists(uuid)) {
            return false;
        }
        try {
            log.info("[FS] 挂断通道 uuid={} reason={}", uuid.trim(),
                    StringUtils.hasText(reason) ? reason : "unspecified");
            EslResponse r = api("uuid_kill " + uuid.trim());
            return r.isOk();
        } catch (Exception e) {
            log.warn("[FS] 挂断通道失败 uuid={}: {}", uuid, e.getMessage());
            return false;
        }
    }

    public String uuidGetVar(String uuid, String varName) {
        EslResponse r = api("uuid_getvar " + uuid.trim() + " " + varName);
        String body = r.getBody() != null ? r.getBody().trim() : "";
        if (isMeaningfulVar(body)) {
            return body;
        }
        String reply = r.getReplyText() != null ? r.getReplyText().trim() : "";
        if (reply.startsWith("+OK") && reply.length() > 4) {
            String tail = reply.substring(3).trim();
            if (isMeaningfulVar(tail)) {
                return tail;
            }
        }
        return body;
    }

    /** 解析 uuid_dump，uuid_getvar 全为 _undef_ 时用于摘机判断 */
    public Map<String, String> uuidDumpVars(String uuid) {
        Map<String, String> out = new LinkedHashMap<>();
        if (!StringUtils.hasText(uuid)) {
            return out;
        }
        try {
            EslResponse r = api("uuid_dump " + uuid.trim());
            String body = r.getBody();
            if (!StringUtils.hasText(body) || body.contains("-ERR")) {
                return out;
            }
            for (String line : body.split("\n")) {
                int idx = line.indexOf(':');
                if (idx <= 0) {
                    continue;
                }
                String key = line.substring(0, idx).trim();
                String val = line.substring(idx + 1).trim();
                if (StringUtils.hasText(key) && isMeaningfulVar(val)) {
                    out.put(key, val);
                }
            }
        } catch (Exception e) {
            log.debug("uuid_dump 失败 uuid={}: {}", uuid, e.getMessage());
        }
        return out;
    }

    /** 通道已接通（客户真实摘机，不含 EARLY MEDIA 回铃） */
    public boolean isChannelAnswered(String uuid) {
        if (!uuidExists(uuid)) {
            return false;
        }
        if (isPositiveEpoch(uuidGetVar(uuid, "answer_epoch"))
                || isPositiveEpoch(uuidGetVar(uuid, "answered_time"))) {
            return true;
        }
        for (String var : List.of("callstate", "channel_call_state", "state", "Channel-Call-State")) {
            if (isActiveCallState(uuidGetVar(uuid, var))) {
                return true;
            }
        }
        String disp = uuidGetVar(uuid, "endpoint_disposition");
        if (isMeaningfulVar(disp)) {
            String upper = disp.toUpperCase();
            if (!upper.contains("EARLY") && upper.contains("ANSWER")) {
                return true;
            }
        }
        if (isAnsweredFromDump(uuid)) {
            return true;
        }
        return isAnsweredFromShowChannels(uuid);
    }

    /** 外呼 park 场景：确保 SIP 200 与双向媒体，避免已摘机但无声 */
    public void ensureOutboundMediaReady(String uuid) {
        if (!StringUtils.hasText(uuid) || !uuidExists(uuid)) {
            return;
        }
        try {
            if (!isChannelAnswered(uuid)) {
                api("uuid_answer " + uuid.trim());
            }
            api("uuid_audio " + uuid.trim() + " start read write");
            api("uuid_setvar " + uuid.trim() + " mute_read false");
            api("uuid_setvar " + uuid.trim() + " mute_write false");
        } catch (Exception e) {
            log.warn("ensureOutboundMediaReady 失败 uuid={}: {}", uuid, e.getMessage());
        }
    }

    private boolean isAnsweredFromDump(String uuid) {
        Map<String, String> dump = uuidDumpVars(uuid);
        if (dump.isEmpty()) {
            return false;
        }
        for (String key : List.of("answer_epoch", "variable_answer_epoch", "answered_time")) {
            if (isPositiveEpoch(dump.get(key))) {
                return true;
            }
        }
        for (String key : List.of("Channel-Call-State", "channel_call_state", "Call-State", "callstate")) {
            if (isActiveCallState(dump.get(key))) {
                return true;
            }
        }
        String disp = firstNonEmpty(dump.get("endpoint_disposition"), dump.get("variable_endpoint_disposition"));
        if (isMeaningfulVar(disp)) {
            String upper = disp.toUpperCase();
            if (!upper.contains("EARLY") && upper.contains("ANSWER")) {
                return true;
            }
        }
        String cs = firstNonEmpty(dump.get("Channel-State"), dump.get("channel_state"));
        if (isMeaningfulVar(cs)) {
            String u = cs.toUpperCase();
            if (u.contains("CS_EXCHANGE_MEDIA") || u.contains("CS_PARK")) {
                String ccs = firstNonEmpty(dump.get("Channel-Call-State"), dump.get("channel_call_state"));
                if (isActiveCallState(ccs)) {
                    return true;
                }
            }
        }
        try {
            EslResponse r = api("uuid_dump " + uuid.trim());
            String body = r.getBody();
            if (StringUtils.hasText(body)) {
                var m = ANSWER_EPOCH_IN_DUMP.matcher(body);
                if (m.find() && isPositiveEpoch(m.group(1))) {
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private boolean isAnsweredFromShowChannels(String uuid) {
        try {
            EslResponse r = api("show channels like " + uuid.trim());
            String body = r.getBody();
            if (!StringUtils.hasText(body) || !body.contains(uuid.trim())) {
                return false;
            }
            String upper = body.toUpperCase();
            if (upper.contains("RINGING") || upper.contains("EARLY")) {
                return false;
            }
            if (upper.contains("ACTIVE") || upper.contains("CS_EXCHANGE_MEDIA")) {
                return true;
            }
            return upper.contains("CS_PARK") && upper.contains("ACTIVE");
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isActiveCallState(String v) {
        if (!isMeaningfulVar(v)) {
            return false;
        }
        String u = v.trim().toUpperCase();
        return "ACTIVE".equals(u) || "HELD".equals(u);
    }

    private static boolean isMeaningfulVar(String v) {
        if (!StringUtils.hasText(v)) {
            return false;
        }
        String t = v.trim();
        return !"_undef_".equals(t) && !"-ERR".equals(t) && !t.startsWith("-ERR ");
    }

    private static String firstNonEmpty(String... vals) {
        for (String v : vals) {
            if (isMeaningfulVar(v)) {
                return v;
            }
        }
        return "";
    }

    private static boolean isPositiveEpoch(String v) {
        if (!isMeaningfulVar(v)) {
            return false;
        }
        try {
            return Long.parseLong(v.trim()) > 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    public String getLastError() {
        return lastError;
    }

    public boolean reachable() {
        lastError = null;
        try {
            EslResponse r = api("status");
            if (r.getReplyText() != null && r.getReplyText().contains("+OK")) {
                return true;
            }
            // api status 返回 api/response，正文含 FreeSWITCH is ready，无 +OK 前缀
            String body = r.getBody();
            return body != null && body.contains("FreeSWITCH");
        } catch (Exception e) {
            lastError = e.getMessage();
            log.warn("ESL 不可达 {}: {}", props.eslEndpoint(), lastError);
            return false;
        }
    }

    private EslResponse execute(String fullCommand) {
        String host = props.getHost();
        int port = props.getEslPort();
        int timeout = props.getConnectTimeoutMs();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeout);
            socket.setSoTimeout(props.getReadTimeoutMs());
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            BufferedWriter out = new BufferedWriter(
                    new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));

            EslResponse welcome = readMessage(in);
            String welcomeType = welcome.getHeaders().getOrDefault("Content-Type", "");
            if (welcomeType.contains("rude-rejection") || welcome.getBody().contains("Access Denied")) {
                throw new BizException(
                        "ESL 拒绝连接（IP 未在白名单）。请在 FS autoload_configs/event_socket.conf.xml "
                                + "的 apply-inbound-acl 中放行本机 IP，或临时改为 loopback.auto");
            }
            if (!welcomeType.contains("auth/request")) {
                throw new BizException("ESL 握手异常: " + welcomeType + " " + welcome.getBody());
            }
            sendRaw(out, "auth " + props.getEslPassword() + "\n\n");
            EslResponse authResp = readMessage(in);
            if (authResp.getReplyText() == null || !authResp.getReplyText().contains("+OK")) {
                throw new BizException("ESL 认证失败（请核对 esl-password 与 FS event_socket 密码）: "
                        + authResp.getReplyText());
            }

            sendRaw(out, fullCommand + "\n\n");
            return readMessage(in);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException("ESL 连接失败 " + host + ":" + port + " — " + e.getMessage());
        }
    }

    private void sendRaw(BufferedWriter out, String text) throws IOException {
        out.write(text);
        out.flush();
    }

    private EslResponse readMessage(BufferedReader in) throws IOException {
        Map<String, String> headers = new LinkedHashMap<>();
        String line;
        while ((line = in.readLine()) != null && !line.isEmpty()) {
            int idx = line.indexOf(':');
            if (idx > 0) {
                headers.put(line.substring(0, idx).trim(), line.substring(idx + 1).trim());
            }
        }
        StringBuilder body = new StringBuilder();
        if (headers.containsKey("Content-Length")) {
            int len = Integer.parseInt(headers.get("Content-Length"));
            char[] buf = new char[len];
            int read = in.read(buf, 0, len);
            if (read > 0) {
                body.append(buf, 0, read);
            }
        } else {
            in.ready();
            while (in.ready()) {
                body.append((char) in.read());
            }
        }
        EslResponse r = new EslResponse();
        r.setHeaders(headers);
        r.setBody(body.toString().trim());
        r.setReplyText(headers.getOrDefault("Reply-Text", r.getBody()));
        return r;
    }

    @Data
    public static class EslResponse {
        private Map<String, String> headers;
        private String body;
        private String replyText;

        public boolean isOk() {
            return replyText != null && replyText.startsWith("+OK");
        }
    }
}
