# FreeSWITCH 可用配置（AI-CALL）

安装目录：`C:\Program Files\FreeSWITCH\conf`

## 保留的网关（实测）

| 网关名 | 账号 | 注册域/代理 | 注册 | 用途 |
|--------|------|-------------|------|------|
| 343543658 | 343543658 | 222.186.39.119 | 可 REGED | **当前主用** |
| gw-400 | 20260318001 | 47.97.172.104 | 可 REGED | 备用 |
| gw-wh | 20260318002 | 47.97.172.104 | 可 REGED | 备用 |
| gw-ipwhite | 20261019001 | 47.97.172.104 | 不注册 | 白名单直拨备用 |

已移除不可用：`343543656`（注册失败 FAIL_WAIT）。

## 安装（管理员 PowerShell）

```powershell
cd D:\work\ai_call\AI-CALL\deploy\freeswitch
powershell -ExecutionPolicy Bypass -File .\install-to-fs.ps1
```

## 说明

- 线路方可能夜间禁呼，白天再测外呼振铃。
- 网关名必须等于管理后台 `line.sip_account`。
- 分机软电话：本机局域网 IP 端口 5060，账号 1001，密码 1234。
- ESL：127.0.0.1:8021 / ClueCon（与 application.yml 一致）。
