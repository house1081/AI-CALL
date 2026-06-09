package com.aicall.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "freeswitch")
public class FreeSwitchProperties {
    /** false=模拟外呼；true=走 ESL 发起（需部署 FreeSWITCH） */
    private boolean enabled = false;
    /** FreeSWITCH 主机 IP/域名 */
    private String host = "192.168.60.28";
    /** SIP 信令端口（线路对接、sofia profile） */
    private int sipPort = 5060;
    /** ESL / Event Socket 控制端口 */
    private int eslPort = 8080;
    private String eslPassword = "ClueCon";
    /** 回调鉴权密钥，留空则不校验 */
    private String callbackSecret = "";
    /** 外呼 gateway 名称（sofia profile 下 gateway 名） */
    private String gateway = "default";
    /** Java 回调根地址，FS 脚本 curl 用（须 FS 能访问） */
    private String callbackBaseUrl = "http://127.0.0.1:8081";
    /** originate 超时（秒） */
    private int originateTimeoutSec = 60;
    /** originate 接通后应用，与 fs_cli 测试一致可用 &echo；outbound-mode=socket 时不使用 */
    private String originateApplication = "&echo";
    /**
     * direct=ESL 直连 gateway；socket=originate 进 dialplan，由 FS socket 连 Java 8888 完成 bridge+对话
     */
    private String outboundMode = "direct";
    /** socket 模式 originate 上下文，须含 ai_outbound extension */
    private String outboundContext = "default";
    private int connectTimeoutMs = 5000;
    private int readTimeoutMs = 30000;
    /** 单通外呼结束后自动将任务置为已终止(4)，不再继续拨打同组客户 */
    private boolean taskTerminateAfterCall = true;

    public boolean isSocketOutboundMode() {
        return "socket".equalsIgnoreCase(outboundMode);
    }

    /** 兼容旧配置项 esl-host */
    public String getEslHost() {
        return host;
    }

    public void setEslHost(String eslHost) {
        this.host = eslHost;
    }

    /** SIP 注册/对接地址，供线路配置参考：host:5060 */
    public String sipEndpoint() {
        return host + ":" + sipPort;
    }

    /** ESL 连接地址 */
    public String eslEndpoint() {
        return host + ":" + eslPort;
    }
}
