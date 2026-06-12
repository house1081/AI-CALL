# AI智能外呼平台

私有化部署、多商户计费型 AI 智能外呼平台（V1.0 极简盈利版）

## 项目结构

```
AI-CALL/
├── sql/init.sql           # 数据库初始化脚本
├── ai-call-server/        # 后端 Spring Boot 3
├── ai-call-web/           # 总后台前端 Vue3 + Element Plus
└── ai-call-work/          # 商户端前端 Vue3 + Element Plus
```

## 环境要求

- JDK 17+
- Maven 3.8+
- Node.js 18+
- MySQL 8.0（127.0.0.1:3306，用户 root/root，库名 `ai-call`）
- Redis（127.0.0.1:6379，密码 root）

## 快速启动

### 1. 初始化数据库

**新环境：**
```bash
mysql -h127.0.0.1 -P3306 -uroot -proot < sql/init.sql
```

**已有 v1.0 库升级：**
```bash
mysql -h127.0.0.1 -P3306 -uroot -proot < sql/upgrade_v1.1.sql
```

详见 [PRD核对清单.md](PRD核对清单.md)、[PRD4-13核对清单.md](PRD4-13核对清单.md)

**计费联调**：见 [docs/计费联调测试.md](docs/计费联调测试.md)

**性能索引升级（已有库）**：
```bash
mysql -h127.0.0.1 -P3306 -uroot -proot ai-call < sql/upgrade_v1.3_simple.sql
```

**FreeSWITCH**（SIP `5060`，ESL `8080`，详见 [docs/FreeSWITCH对接说明.md](docs/FreeSWITCH对接说明.md)）

**FreeSWITCH 回调**（无需 JWT）：
- `POST /api/callback/fs/call-start` — 接通创建会话
- `POST /api/callback/billing/deduct-minute` — 每分钟扣费（传 `callRecordId`）
- `POST /api/callback/fs/call-end` — 挂断结算

### 2. 启动后端

```bash
cd ai-call-server
mvn spring-boot:run
```

服务地址：http://127.0.0.1:8080

### 3. 启动总后台

```bash
cd ai-call-web
npm install
npm run dev
```

访问：http://localhost:5173  
默认账号：`admin` / `admin123`

### 4. 启动商户端

```bash
cd ai-call-work
npm install
npm run dev
```

访问：http://localhost:5174  
商户账号由总后台「商户管理」创建

## 核心功能

### 总后台 (ai-call-web)
- 控制台数据统计
- 线路管理（阿里云 SIP）
- 商户管理 / 充值
- 费率配置
- AI 全局话术配置
- 全局风控 / 黑名单
- 全平台通话记录
- 充值审核

### 商户端 (ai-call-work)
- 首页余额 / 消费概览
- 客户管理（导入、分组、黑名单）
- 外呼任务（创建、启动、暂停、终止）
- 通话记录 / 对话详情
- 意向客户（A/B/C/D）
- 在线充值 / 消费明细

### 计费规则（后端写死）
- 仅接通计费（call_status=1）
- 不足 1 分钟按 1 分钟计费（Math.ceil）
- 实时扣费，余额不足暂停任务
- 每笔记录含 cost_amount、deduct_amount、profit

## 外呼对接说明

当前外呼任务使用**模拟拨号**（`CallTaskRunnerService`），用于开发联调。生产环境需对接：
- **FreeSWITCH**：线路配置、发起外呼、状态回调 → `POST /api/callback/call-end`
- **本地 AI 层**：Ollama `qwen:4b`（默认 `http://192.168.60.28:11434`）+ FunASR + TTS；话术见 `ai_prompt`，对话见 `/api/ai/chat`（详见 [docs/Ollama对接说明.md](docs/Ollama对接说明.md)）

## 技术栈

| 模块 | 技术 |
|------|------|
| 后端 | Spring Boot 3.2、MyBatis-Plus、JWT、Redis |
| 前端 | Vue 3、Vite、Element Plus、Axios |
