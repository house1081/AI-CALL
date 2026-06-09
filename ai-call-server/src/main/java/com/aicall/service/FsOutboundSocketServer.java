package com.aicall.service;

import com.aicall.config.AiVoiceProperties;
import com.aicall.config.FreeSwitchProperties;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 监听 8888，接收 FreeSWITCH dialplan {@code socket ... sync full} 连接。
 */
@Slf4j
@Component
@Order(30)
@RequiredArgsConstructor
public class FsOutboundSocketServer implements ApplicationRunner {

    private final FreeSwitchProperties freeSwitchProperties;
    private final AiVoiceProperties aiVoiceProperties;
    private final OutboundSocketDialogService outboundSocketDialogService;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private ServerSocket serverSocket;
    private ExecutorService acceptExecutor;
    private ExecutorService callExecutor;

    @Override
    public void run(ApplicationArguments args) {
        if (!freeSwitchProperties.isEnabled() || !freeSwitchProperties.isSocketOutboundMode()) {
            log.info("【8888】未启用 socket 外呼模式（freeswitch.outbound-mode=direct）");
            return;
        }
        if (!aiVoiceProperties.isFsSocketEnabled()) {
            log.warn("【8888】socket 外呼已配置但 ai-voice.fs-socket-enabled=false");
            return;
        }
        acceptExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "fs-outbound-socket-accept");
            t.setDaemon(true);
            return t;
        });
        callExecutor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "fs-outbound-socket-call");
            t.setDaemon(true);
            return t;
        });
        acceptExecutor.submit(this::acceptLoop);
    }

    private void acceptLoop() {
        String host = aiVoiceProperties.getFsSocketHost();
        int port = aiVoiceProperties.getFsSocketPort();
        try {
            serverSocket = new ServerSocket();
            serverSocket.bind(new InetSocketAddress(host, port));
            running.set(true);
            log.info("【8888】Outbound Socket 已监听 {}:{}（FS dialplan socket 应对准此地址）", host, port);
            while (running.get()) {
                Socket client = serverSocket.accept();
                callExecutor.submit(() -> outboundSocketDialogService.handle(client));
            }
        } catch (Exception e) {
            if (running.get()) {
                log.error("【8888】监听异常: {}", e.getMessage(), e);
            }
        }
    }

    @PreDestroy
    public void shutdown() {
        running.set(false);
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (Exception ignored) {
        }
        if (acceptExecutor != null) {
            acceptExecutor.shutdownNow();
        }
        if (callExecutor != null) {
            callExecutor.shutdownNow();
        }
    }

    public boolean isRunning() {
        return running.get();
    }

    public int getPort() {
        return aiVoiceProperties.getFsSocketPort();
    }
}
