package com.aicall.fs;

import java.io.BufferedWriter;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/** 向 FS Outbound Socket 发送 ESL 风格命令 */
public final class FsOutboundSocketWriter {

    private final BufferedWriter out;

    public FsOutboundSocketWriter(Socket socket) throws IOException {
        this.out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
    }

    public void connect() throws IOException {
        sendRaw("connect\n\n");
    }

    public void myEvents() throws IOException {
        sendRaw("myevents\n\n");
    }

    public void eventPlain(String events) throws IOException {
        sendRaw("event plain " + events + "\n\n");
    }

    public void linger() throws IOException {
        sendRaw("linger\n\n");
    }

    public void execute(String app, String arg) throws IOException {
        execute(app, arg, false);
    }

    public void execute(String app, String arg, boolean eventLock) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("sendmsg\n");
        sb.append("call-command: execute\n");
        sb.append("execute-app-name: ").append(app).append('\n');
        sb.append("execute-app-arg: ").append(arg == null ? "" : arg).append('\n');
        if (eventLock) {
            sb.append("event-lock: true\n");
        }
        sb.append("\n");
        sendRaw(sb.toString());
    }

    public void sendRaw(String text) throws IOException {
        out.write(text);
        out.flush();
    }

    /** 读取 FS 对 connect / sendmsg 等的回复 */
    public FsSocketMessage readReply(BufferedReader in) throws IOException {
        return FsSocketMessage.read(in);
    }

    public void closeQuietly() {
        try {
            out.close();
        } catch (IOException ignored) {
        }
    }
}
