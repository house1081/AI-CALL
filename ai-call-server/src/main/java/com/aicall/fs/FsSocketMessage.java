package com.aicall.fs;

import lombok.Data;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/** FreeSWITCH Event Socket 文本消息（Inbound/Outbound 通用） */
@Data
public class FsSocketMessage {

    private Map<String, String> headers = new LinkedHashMap<>();
    private String body = "";

    public static FsSocketMessage read(BufferedReader in) throws IOException {
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
            int read = 0;
            while (read < len) {
                int n = in.read(buf, read, len - read);
                if (n < 0) {
                    break;
                }
                read += n;
            }
            body.append(buf, 0, read);
        }
        FsSocketMessage msg = new FsSocketMessage();
        msg.setHeaders(headers);
        msg.setBody(body.toString());
        return msg;
    }

    /** 将 body 中的 key: value 行合并进 headers（FS outbound 通道变量常在 body 里） */
    public FsSocketMessage mergeBodyAsHeaders() {
        FsSocketMessage out = new FsSocketMessage();
        out.getHeaders().putAll(this.headers);
        parseKeyValueLines(this.body, out.getHeaders());
        out.setBody(this.body);
        return out;
    }

    public void mergeFrom(FsSocketMessage other) {
        if (other == null) {
            return;
        }
        this.headers.putAll(other.getHeaders());
        if (other.getBody() != null && !other.getBody().isBlank()) {
            parseKeyValueLines(other.getBody(), this.headers);
        }
    }

    private static void parseKeyValueLines(String text, Map<String, String> into) {
        if (text == null || text.isBlank()) {
            return;
        }
        for (String line : text.split("\\r?\\n")) {
            int idx = line.indexOf(':');
            if (idx > 0) {
                into.put(line.substring(0, idx).trim(), line.substring(idx + 1).trim());
            }
        }
    }

    public String header(String name) {
        return headers.getOrDefault(name, "");
    }

    /** 忽略大小写匹配 header 名 */
    public String headerIgnoreCase(String name) {
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey().equalsIgnoreCase(name)) {
                return e.getValue() != null ? e.getValue().trim() : "";
            }
        }
        return "";
    }

    /** 匹配 header 名或 variable_ 前缀 */
    public String channelVar(String name) {
        String v = headerIgnoreCase(name);
        if (hasText(v)) {
            return v;
        }
        v = headerIgnoreCase("variable_" + name);
        if (hasText(v)) {
            return v;
        }
        return "";
    }

    private static boolean hasText(String v) {
        return v != null && !v.isBlank() && !"_undef_".equals(v);
    }

    public String eventName() {
        return header("Event-Name");
    }

    public String channelUuid() {
        String u = header("Unique-ID");
        if (u.isEmpty()) {
            u = header("Channel-UUID");
        }
        return u;
    }
}
