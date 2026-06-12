# Ollama（qwen:4b）对接说明

## 配置

`application.yml`：

```yaml
ollama:
  base-url: http://192.168.60.28:11434
  model: qwen:4b
```

确保 Ollama 主机已拉取模型：

```bash
ollama pull qwen:4b
ollama list
```

后端服务需能访问 `192.168.60.28:11434`（防火墙放行）。

## 接口（免 JWT，内网调用）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/ai/health` | 检测 Ollama 是否可达、模型是否已安装 |
| GET | `/api/ai/config` | 当前模型与参数 |
| GET | `/api/ai/active-prompt` | 启用中的话术模板 |
| POST | `/api/ai/chat` | 实时对话 |
| POST | `/api/ai/summarize` | 通话结束提取意向 |

## 对话示例

**首轮（开场白，不调模型）：**

```bash
curl -X POST http://127.0.0.1:8080/api/ai/chat ^
  -H "Content-Type: application/json" ^
  -d "{\"firstTurn\":true}"
```

**客户说话后：**

```bash
curl -X POST http://127.0.0.1:8080/api/ai/chat ^
  -H "Content-Type: application/json" ^
  -d "{\"userText\":\"你们怎么收费的？\",\"history\":[{\"role\":\"assistant\",\"content\":\"您好...\"},{\"role\":\"user\",\"content\":\"喂\"}]}"
```

**通话结束提取：**

```bash
curl -X POST http://127.0.0.1:8080/api/ai/summarize ^
  -H "Content-Type: application/json" ^
  -d "{\"dialogText\":\"销售：您好... 客户：有点兴趣，预算大概十万，下周再联系\"}"
```

## 与外呼链路关系

```
客户语音 → FunASR → POST /api/ai/chat → 回复文本 → TTS 播报
通话结束 → POST /api/ai/summarize → 写入 call_record 五字段 → POST /api/callback/call-end
```

话术在管理后台「AI 话术」维护，保存后 `/api/ai/active-prompt` 即时生效。
