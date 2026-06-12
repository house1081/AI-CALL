# 本机安装 FreeSWITCH（Windows 开发机）

与本项目 Java（8081）同机或同局域网部署 FS，可解决 **60 网段 FS 访问不到 61 网段 Java** 导致的 curl/播报失败。

推荐方式：**WSL2 + Ubuntu 安装 FS**（稳定、与线上一致）。若使用 **Windows 原生 FS**，必须手动启用 `mod_sofia`（见下文「常见错误」）。

---

## 常见错误：`Could not locate channel type sofia` / `CHAN_NOT_IMPLEMENTED`

说明 **SIP 模块 mod_sofia 未加载**，外呼 `sofia/gateway/...` 会失败。

### Windows 原生 FS 处理

1. 打开 FS 安装目录，例如 `C:\Program Files\FreeSWITCH\conf\autoload_configs\modules.conf.xml`
2. 确认存在并**取消注释**（去掉 `<!--` `-->`）：

```xml
<load module="mod_sofia"/>
```

3. 重启 FreeSWITCH 服务，或在 `fs_cli` 中执行：

```text
load mod_sofia
reload mod_sofia
sofia status
```

4. `sofia status` 应能看到 `profile external` 等；再配置网关（见下文）。

### WSL / Linux

```bash
sudo apt install freeswitch-mod-sofia
# 或 modules.conf.xml 中 load mod_sofia
sudo systemctl restart freeswitch
fs_cli -x "sofia status"
```

**未加载 sofia 时，Java 侧 ESL 可能仍显示可达，但 originate 一定失败。**

---

## 一、WSL2 安装 FreeSWITCH（推荐）

### 1. 启用 WSL2 并安装 Ubuntu

PowerShell（管理员）：

```powershell
wsl --install -d Ubuntu
```

重启后进入 Ubuntu，更新系统：

```bash
sudo apt update && sudo apt upgrade -y
```

### 2. 安装 FreeSWITCH

```bash
sudo apt install -y freeswitch freeswitch-mod-sofia freeswitch-mod-event-socket
```

若仓库版本过旧，可按 [FreeSWITCH 官方文档](https://developer.signalwire.com/freeswitch/FreeSWITCH-Explained/Installation/Linux/Debian_67240088/) 添加 SignalWire 源。

### 3. 开启 ESL（8021）

编辑 `/etc/freeswitch/autoload_configs/event_socket.conf.xml`：

- `listen-ip`：`0.0.0.0`（允许 Windows 上的 Java 连接）
- `listen-port`：`8021`
- `password`：`ClueCon`（与 `application.yml` 中 `esl-password` 一致）
- `apply-inbound-acl`：开发环境可临时改为 `any_v4` 或增加 ACL 放行 Windows 主机 IP

```bash
sudo systemctl enable freeswitch
sudo systemctl restart freeswitch
sudo fs_cli -x "status"
```

### 4. 配置 SIP 网关（与后台线路表一致）

在 FS 中新增 gateway，**名称必须与线路管理「SIP账号」完全一致**（例如 `343543656`）。

`/etc/freeswitch/sip_profiles/external/343543656.xml` 示例：

```xml
<include>
  <gateway name="343543656">
    <param name="realm" value="222.186.39.119"/>
    <param name="proxy" value="222.186.39.119:5060"/>
    <param name="register" value="true"/>
    <param name="username" value="343543656"/>
    <param name="password" value="你的线路密码"/>
    <param name="register-transport" value="udp"/>
  </gateway>
</include>
```

```bash
sudo fs_cli -x "sofia profile external rescan"
sudo fs_cli -x "sofia status gateway 343543656"
```

### 5. WSL 中测试外呼

```bash
sudo fs_cli -x "originate {origination_uuid=test-1}sofia/gateway/343543656/你的手机号 &park()"
```

---

## 二、Java 配置（本机 FS）

### 方式 A：使用 profile（推荐）

启动时加：

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=local-fs
```

或 IDE 激活 profile：`local-fs`（见 `application-local-fs.yml`）。

### 方式 B：直接改 `application.yml`

```yaml
freeswitch:
  enabled: true
  host: 127.0.0.1      # WSL 端口会转发到 Windows
  esl-port: 8021
  esl-password: ClueCon
  callback-base-url: http://127.0.0.1:8081

ai-voice:
  playback-base-url: http://127.0.0.1:8081   # 见下方「重要」
  fs-temp-wav-dir: /tmp/aicall-voice
  fs-push-wav-via-esl: true
  fs-fetch-via-esl: true
```

### 重要：Java 在 Windows、FS 在 WSL2

WSL 里的 `127.0.0.1` 是 WSL 自己，**不是** Windows。FS 执行 `curl` 拉 wav 时需填 **Windows 主机在 WSL 网内的 IP**：

```bash
# 在 WSL 里查看 Windows 主机 IP
cat /etc/resolv.conf | grep nameserver | awk '{print $2}'
# 例如 172.24.176.1
```

则 `playback-base-url` 设为：

```yaml
playback-base-url: http://172.24.176.1:8081
```

并在 **Windows 防火墙** 放行入站 **8081**。

自检（在 WSL 里）：

```bash
curl -I http://172.24.176.1:8081/uploads/tts/startup-beep.wav
```

### FS 与 Java 都在 WSL 内

`playback-base-url: http://127.0.0.1:8081` 即可。

---

## 三、自检清单

| 检查项 | 命令/方法 |
|--------|-----------|
| ESL 可达 | `curl http://127.0.0.1:8081/api/callback/fs/health` → `eslReachable: true` |
| FS 状态 | `fs_cli -x "status"` |
| 网关注册 | `fs_cli -x "sofia status gateway 343543656"` → REGED |
| Java 静态 wav | 浏览器打开 `http://127.0.0.1:8081/uploads/tts/startup-beep.wav` |
| WSL 拉 wav | WSL 内 `curl -I http://<Windows主机IP>:8081/uploads/tts/startup-beep.wav` |

---

## 四、Docker 方式（可选）

```bash
docker run -d --name fs \
  -p 5060:5060/udp -p 8021:8021/tcp \
  -v ./fs-config:/etc/freeswitch \
  safarov/freeswitch
```

需自行挂载 `event_socket.conf`、gateway XML，与上文 WSL 配置相同。

---

## 五、与本项目的关系

- 外呼仍由 Java **ESL originate** 下发，线路表 **SIP账号 = gateway 名** 不变。
- 本机 FS 后，优先 **FS curl `playback-base-url`** 或 **ESL 分片推送**，语音播报成功率更高。
- 远程 FS（192.168.60.28）可保留作生产；本机 FS 仅开发联调时改 `host` 与 `playback-base-url` 即可。

更多排障见 [语音外呼无声音排查.md](语音外呼无声音排查.md)、[真实外呼部署说明.md](真实外呼部署说明.md)。
