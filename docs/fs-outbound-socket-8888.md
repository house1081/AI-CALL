# FreeSWITCH Outbound Socket 8888 人机对话闭环

## 架构

```
Java originate → FS dialplan(ai_outbound) → socket 8888 → Java bridge 网关
  → 接通 → 开场白(TTS) → 录音 → ASR → LLM(voice-turn) → TTS → 循环
```

## 配置（application.yml）

```yaml
freeswitch:
  outbound-mode: socket      # direct | socket
  outbound-context: default

ai-voice:
  auto-play-after-originate: false   # socket 模式必须 false
  fs-socket-enabled: true
  fs-socket-port: 8888
  asr-http-url: ""                   # FunASR；留空则用 dashscope.api-key
```

## FreeSWITCH dialplan（default.xml）

**重要：须用 `sync full`（勿用 `async`，否则 FS 会秒断 8888）；bridge 由 Java 执行，dialplan 里不要再写 bridge。**

```xml
<extension name="ai_outbound">
  <condition field="destination_number" expression="^(.*)$">
    <action application="set" data="max_call_seconds=90"/>
    <action application="socket" data="192.168.61.109:8888 sync full"/>
  </condition>
</extension>
```

`192.168.61.109` 改为运行 Java 的机器 IP（FS 能访问）。

## 外呼 originate 格式（socket 模式）

Java 自动下发（须 **loopback**，不能写裸号码）：

```
{origination_uuid=...,ai_gateway=343543656,...}loopback/被叫号码/default &park()
```

错误示例（会报 `Could not locate channel type 18154227651`）：

```
{...}18154227651 XML default
```

## 健康检查

```http
GET http://127.0.0.1:8081/api/callback/fs/health
```

关注字段：

- `outboundMode`: `socket`
- `fsSocketRunning`: `true`
- `fsSocketPort`: `8888`
- `asrDashScopeConfigured` 或配置 `asrHttpUrl`

## ASR

1. **FunASR**（推荐本机）：`ai-voice.asr-http-url: http://127.0.0.1:10095/api/v1/asr`
2. **DashScope**：配置 `dashscope.api-key`，自动走 `paraformer-v2` 同步识别

## 日志关键字

- `【8888】Outbound Socket 已监听` — 服务启动成功
- `[8888] 收到通道 uuid=...` — FS 已连上
- `[8888] bridge uuid=...` — 正在桥接网关
- `[8888] ASR uuid=... text=...` — 识别成功
- `voice-turn` / `接通播报` — LLM + TTS

## 切回 direct 模式（仅播开场白、无 ASR 循环）

```yaml
freeswitch:
  outbound-mode: direct
ai-voice:
  auto-play-after-originate: true
```
