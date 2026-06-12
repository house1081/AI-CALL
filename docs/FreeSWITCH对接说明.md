# FreeSWITCH 对接说明

## 端口（当前环境）

| 用途 | 端口 | 说明 |
|------|------|------|
| **SIP 信令** | **5060** | 运营商/网关 SIP 中继、sofia 注册 |
| **ESL 控制** | **8080** | Event Socket，外呼控制、事件订阅 |

## 后端配置 `application.yml`

```yaml
server:
  port: 8080   # Java 后台；若与 FS 的 ESL 8080 在同一台机器，请改为 8081 避免冲突

freeswitch:
  enabled: false
  host: 192.168.60.28    # FreeSWITCH 服务器 IP
  sip-port: 5060
  esl-port: 8080
  esl-password: ClueCon
  gateway: default
```

## 管理后台线路表

`line.sip_address` 填写网关地址，格式示例：

```
192.168.60.28:5060
```

或运营商中继：

```
xxx.aliyuncs.com:5060
```

## 回调接口（FS → Java）

| 接口 | 时机 |
|------|------|
| `POST /api/callback/fs/call-start` | 接通建立会话 |
| `POST /api/callback/billing/deduct-minute` | 每满 1 分钟扣费 |
| `POST /api/callback/fs/call-end` | 挂断结算 |
| `GET /api/callback/fs/call-tick?callRecordId=` | 每秒校验是否达 90 秒 |
| `POST /api/callback/fs/hangup` | 强制挂断结算（播完结束语后调用） |

检查配置：

```bash
curl http://127.0.0.1:8080/api/callback/fs/health
```

## 与 Ollama / 语音层

```
SIP(5060) 通话 → FS dialplan → HTTP 回调 Java(8080/8081)
ASR 文本 → POST /api/ai/chat（带 callRecordId）→ Ollama → TTS 回 FS 播放  
若响应 `shouldHangup=true`：先 TTS 播放 `endWords`，再 `POST /api/callback/fs/hangup`
```

## 注意

1. **8080 端口**：Spring Boot 默认也用 8080；FS ESL 若也是 8080 且在同一主机，必须错开其中一个端口。  
2. 生产将 `freeswitch.enabled` 设为 `true` 并实现 ESL `originate`（当前为桩，日志提示接线方式）。  
3. 可选 `freeswitch.callback-secret`，FS 脚本请求时加 Header：`X-Callback-Secret`。
