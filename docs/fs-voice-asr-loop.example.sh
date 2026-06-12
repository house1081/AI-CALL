#!/bin/bash
# FreeSWITCH 侧语音循环示例（需本机 curl、FunASR 出字后调用）
# Java 基址：与 application.yml 中 callback-base-url / playback-base-url 一致
JAVA_BASE="${JAVA_BASE:-http://192.168.60.28:8081}"
FS_UUID="${1:?用法: $0 <通道uuid> <callRecordId>}"
CALL_RECORD_ID="${2:?缺少 callRecordId}"

# 示例：从 FunASR 得到一句 user_text 后调用
user_text="${USER_TEXT:-你好}"

curl -s -X POST "${JAVA_BASE}/api/callback/fs/voice-turn" \
  -H "Content-Type: application/json" \
  -d "{\"fsUuid\":\"${FS_UUID}\",\"callRecordId\":${CALL_RECORD_ID},\"userText\":\"${user_text}\",\"history\":[]}"

echo ""
