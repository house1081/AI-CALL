# FS 报错：Could not locate channel type sofia

## 日志含义

```
[ERR] Could not locate channel type sofia
[NOTICE] Cannot create outgoing channel of type [sofia] cause: [CHAN_NOT_IMPLEMENTED]
```

**原因：** 本机 FreeSWITCH **没有加载 `mod_sofia`**（SIP 协议栈），无法执行 Java 下发的：

`originate ... sofia/gateway/343543656/手机号 ...`

## 处理步骤（Windows）

1. 编辑 `conf/autoload_configs/modules.conf.xml`（FS 安装目录下）
2. 启用：

```xml
<load module="mod_sofia"/>
```

3. 重启 FS，进入 `fs_cli`：

```text
sofia status
```

应看到 Profile 列表，而不是报错。

4. 配置外网 profile 与网关（与后台线路「SIP账号」一致），例如：

`conf/sip_profiles/external/343543656.xml`

5. 测试：

```text
sofia status gateway 343543656
originate {origination_uuid=test-1}sofia/gateway/343543656/你的手机号 &answer(),playback(silence_stream://-1)
```

## 与 Java 的关系

| 检查项 | 说明 |
|--------|------|
| ESL 8021 可达 | 只说明 Event Socket 正常 |
| sofia 已加载 | **外呼必要条件** |
| 网关 REGED | 线路能真正拨出去 |

Java `application.yml` 无需修改；修好 FS 模块后即可再试外呼任务。
