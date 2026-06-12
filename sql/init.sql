CREATE DATABASE IF NOT EXISTS `ai-call` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE `ai-call`;

-- 管理员
CREATE TABLE IF NOT EXISTS `admin_user` (
  `id` int NOT NULL AUTO_INCREMENT,
  `username` varchar(50) NOT NULL,
  `password` varchar(100) NOT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 密码 admin123 的 MD5（与后端 Md5Util 一致，勿用明文）
INSERT INTO `admin_user` (`username`, `password`) VALUES ('admin', '0192023a7bbd73250516f069df18b500')
ON DUPLICATE KEY UPDATE `password` = VALUES(`password`);

-- 费率配置
CREATE TABLE IF NOT EXISTS `price_config` (
  `id` int NOT NULL AUTO_INCREMENT,
  `price_type` tinyint NOT NULL COMMENT '1零售 2企业 3代理',
  `default_sell_price` decimal(10,4) NOT NULL,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_price_type` (`price_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO `price_config` (`price_type`, `default_sell_price`) VALUES
(1, 0.1500), (2, 0.1200), (3, 0.0900);

-- 线路表
CREATE TABLE IF NOT EXISTS `line` (
  `id` int NOT NULL AUTO_INCREMENT,
  `sip_account` varchar(50) NOT NULL,
  `sip_password` varchar(50) NOT NULL,
  `sip_address` varchar(100) NOT NULL,
  `cost_price` decimal(10,4) NOT NULL DEFAULT 0.0600,
  `status` tinyint NOT NULL DEFAULT 1 COMMENT '0禁用 1启用',
  `daily_call_limit` int NOT NULL DEFAULT 1000,
  `current_concurrent` int NOT NULL DEFAULT 0,
  `today_call_count` int NOT NULL DEFAULT 0,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_sip_account` (`sip_account`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 商户表
CREATE TABLE IF NOT EXISTS `tenant` (
  `id` int NOT NULL AUTO_INCREMENT,
  `username` varchar(50) NOT NULL,
  `password` varchar(100) NOT NULL,
  `contact_name` varchar(30) NOT NULL,
  `contact_phone` varchar(11) NOT NULL,
  `balance` decimal(10,2) NOT NULL DEFAULT 0.00,
  `pending_deduct` decimal(10,4) NOT NULL DEFAULT 0.0000 COMMENT '待补扣',
  `price_type` tinyint NOT NULL DEFAULT 1,
  `sell_price` decimal(10,4) NOT NULL DEFAULT 0.1500,
  `status` tinyint NOT NULL DEFAULT 1,
  `daily_call_limit` int NOT NULL DEFAULT 500,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `last_login_time` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_username` (`username`),
  UNIQUE KEY `uk_contact_phone` (`contact_phone`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 风控配置
CREATE TABLE IF NOT EXISTS `risk_config` (
  `id` int NOT NULL AUTO_INCREMENT,
  `call_interval` int NOT NULL DEFAULT 30,
  `short_call_limit` int NOT NULL DEFAULT 15,
  `call_start_time` varchar(5) NOT NULL DEFAULT '09:00',
  `call_end_time` varchar(5) NOT NULL DEFAULT '19:00',
  `high_complaint_area` varchar(200) DEFAULT NULL,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO `risk_config` (`id`, `call_interval`, `call_start_time`, `call_end_time`) VALUES (1, 10, '08:00', '22:00');

-- 全局黑名单
CREATE TABLE IF NOT EXISTS `global_blacklist` (
  `id` int NOT NULL AUTO_INCREMENT,
  `phone` varchar(11) NOT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_phone` (`phone`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- AI话术
CREATE TABLE IF NOT EXISTS `ai_prompt` (
  `id` int NOT NULL AUTO_INCREMENT,
  `prompt_content` text NOT NULL,
  `opening_remarks` varchar(200) NOT NULL,
  `end_remarks` varchar(100) NOT NULL,
  `is_active` tinyint NOT NULL DEFAULT 1,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO `ai_prompt` (`prompt_content`, `opening_remarks`, `end_remarks`) VALUES (
'你是专业企业电话销售，口语简短自然，每句话控制20秒内。
【强制挂断规则，必须严格执行】
1. 连续闲聊超过4轮、客户不聊业务、一直扯无关话题 → 主动礼貌结束语并挂断
2. 客户明确拒绝、不需要、没时间 → 立即结束语挂断，不纠缠
3. 对话时长超过90秒 → 自动收尾结束语挂断
4. 连续2次主动询问需求、预算、痛点，客户不配合 → 播放固定结束语并触发挂断
固定结束语：感谢您的时间，祝您生活愉快，再见。
识别到挂断条件时除结束语外，回复末尾单独一行输出 [挂断触发]。

业务规则：
1.简洁自我介绍，快速切入业务；
2.主动询问：客户需求、预算、痛点、合作意向；
3.客户问题精准简答，不啰嗦；
4.支持随时被客户打断；
5.通话结束严格输出一行结构化结果，用 | 分隔：
客户需求|客户痛点|预算|最佳回访时间|意向等级(A/B/C/D)
A=高意向 B=中意向 C=低意向 D=无意向',
'您好，打扰您一分钟，想简单了解下您这边是否有相关业务需求？',
'感谢您的时间，祝您生活愉快，再见。'
);

-- AI 模型统一配置
CREATE TABLE IF NOT EXISTS `ai_model_config` (
  `id` int NOT NULL AUTO_INCREMENT,
  `config_name` varchar(100) NOT NULL,
  `provider` varchar(20) NOT NULL COMMENT 'ollama|qwen|wenxin',
  `base_url` varchar(300) NOT NULL DEFAULT '',
  `model_name` varchar(100) NOT NULL DEFAULT '',
  `api_key` varchar(500) DEFAULT '',
  `secret_key` varchar(500) DEFAULT '',
  `max_tokens` int NOT NULL DEFAULT 80,
  `temperature` decimal(3,2) NOT NULL DEFAULT 0.70,
  `max_history_rounds` int NOT NULL DEFAULT 3,
  `connect_timeout_ms` int NOT NULL DEFAULT 5000,
  `read_timeout_ms` int NOT NULL DEFAULT 60000,
  `is_active` tinyint NOT NULL DEFAULT 0,
  `remark` varchar(200) DEFAULT '',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO `ai_model_config` (`config_name`, `provider`, `base_url`, `model_name`, `max_tokens`, `temperature`, `max_history_rounds`, `is_active`, `remark`) VALUES
('本地 Ollama', 'ollama', 'http://192.168.60.28:11434', 'qwen:4b', 80, 0.70, 3, 1, '内网 Ollama'),
('通义千问 DashScope', 'qwen', 'https://dashscope.aliyuncs.com/compatible-mode/v1', 'qwen-turbo', 80, 0.70, 3, 0, '需 API Key'),
('文心一言 千帆', 'wenxin', 'https://aip.baidubce.com/rpc/2.0/ai_custom/v1/wenxinworkshop/chat/completions', 'completions', 80, 0.70, 3, 0, '需 API Key + Secret');

-- 客户分组
CREATE TABLE IF NOT EXISTS `customer_group` (
  `id` int NOT NULL AUTO_INCREMENT,
  `group_name` varchar(50) NOT NULL,
  `tenant_id` int NOT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_tenant` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 客户表
CREATE TABLE IF NOT EXISTS `customer` (
  `id` int NOT NULL AUTO_INCREMENT,
  `phone` varchar(11) NOT NULL,
  `name` varchar(30) DEFAULT NULL,
  `province` varchar(20) DEFAULT NULL COMMENT '省份',
  `group_id` int NOT NULL DEFAULT 1,
  `group_name` varchar(50) NOT NULL DEFAULT '未分组',
  `level` varchar(10) DEFAULT NULL,
  `is_black` tinyint NOT NULL DEFAULT 0,
  `tenant_id` int NOT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `last_call_time` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_phone_tenant` (`phone`, `tenant_id`),
  KEY `idx_tenant` (`tenant_id`),
  KEY `idx_tenant_level` (`tenant_id`, `level`, `last_call_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 外呼任务
CREATE TABLE IF NOT EXISTS `call_task` (
  `id` int NOT NULL AUTO_INCREMENT,
  `task_name` varchar(100) NOT NULL,
  `tenant_id` int NOT NULL,
  `group_id` int NOT NULL,
  `call_count` int NOT NULL DEFAULT 0,
  `completed_count` int NOT NULL DEFAULT 0,
  `success_count` int NOT NULL DEFAULT 0,
  `status` tinyint NOT NULL DEFAULT 0 COMMENT '0未启动 1运行 2暂停 3完成 4终止',
  `dial_mode` tinyint NOT NULL DEFAULT 1 COMMENT '1立即 2定时',
  `scheduled_start_time` datetime DEFAULT NULL COMMENT '定时外呼开始时间',
  `max_ring_count` int NOT NULL DEFAULT 10 COMMENT '最大振铃次数',
  `task_rules` varchar(500) DEFAULT NULL COMMENT '外呼规则说明',
  `auto_add_wechat` tinyint NOT NULL DEFAULT 0 COMMENT '接通后自动加微信',
  `wechat_add_api_url` varchar(255) DEFAULT NULL COMMENT '加V接口地址',
  `wechat_add_message` varchar(500) DEFAULT NULL COMMENT '加V招呼语模板',
  `wechat_add_remark` varchar(200) DEFAULT NULL COMMENT '加V备注模板',
  `start_time` datetime DEFAULT NULL,
  `end_time` datetime DEFAULT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_tenant` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 通话记录
CREATE TABLE IF NOT EXISTS `call_record` (
  `id` int NOT NULL AUTO_INCREMENT,
  `tenant_id` int NOT NULL,
  `line_id` int NOT NULL,
  `customer_id` int DEFAULT NULL,
  `customer_phone` varchar(11) NOT NULL,
  `call_duration` int NOT NULL DEFAULT 0,
  `billed_minutes` int NOT NULL DEFAULT 0,
  `prepaid_amount` decimal(10,4) NOT NULL DEFAULT 0.0000,
  `sell_price_snapshot` decimal(10,4) DEFAULT NULL,
  `cost_price_snapshot` decimal(10,4) DEFAULT NULL,
  `deduct_amount` decimal(10,4) NOT NULL DEFAULT 0.0000,
  `cost_amount` decimal(10,4) NOT NULL DEFAULT 0.0000,
  `profit` decimal(10,4) NOT NULL DEFAULT 0.0000,
  `profit_abnormal` tinyint NOT NULL DEFAULT 0 COMMENT '负毛利',
  `record_url` varchar(200) DEFAULT '',
  `dialog_text` text,
  `customer_need` varchar(200) DEFAULT NULL,
  `customer_pain` varchar(200) DEFAULT NULL,
  `budget` varchar(100) DEFAULT NULL,
  `next_time` varchar(50) DEFAULT NULL,
  `level` varchar(10) NOT NULL DEFAULT 'D',
  `call_status` tinyint NOT NULL COMMENT '1接通 2无人 3空号 4拒接 5失败',
  `hangup_type` varchar(50) DEFAULT NULL COMMENT '正常结束/强制挂断-*',
  `call_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `task_id` int DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_tenant` (`tenant_id`),
  KEY `idx_call_time` (`call_time`),
  KEY `idx_tenant_call_time` (`tenant_id`, `call_time`),
  KEY `idx_tenant_phone_id` (`tenant_id`, `customer_phone`, `id`),
  KEY `idx_profit_abnormal` (`profit_abnormal`, `call_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 充值订单
CREATE TABLE IF NOT EXISTS `recharge_order` (
  `id` int NOT NULL AUTO_INCREMENT,
  `order_no` varchar(50) NOT NULL,
  `tenant_id` int NOT NULL,
  `recharge_amount` decimal(10,2) NOT NULL,
  `arrival_balance` decimal(10,2) DEFAULT NULL,
  `status` tinyint NOT NULL DEFAULT 0 COMMENT '0待审核 1已到账 2失败',
  `voucher_url` varchar(200) DEFAULT NULL,
  `fail_reason` varchar(200) DEFAULT NULL,
  `recharge_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `audit_time` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_order_no` (`order_no`),
  KEY `idx_tenant` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 余额变动明细
CREATE TABLE IF NOT EXISTS `balance_log` (
  `id` int NOT NULL AUTO_INCREMENT,
  `tenant_id` int NOT NULL,
  `type` tinyint NOT NULL COMMENT '1充值 2通话扣费 3补扣',
  `amount` decimal(10,4) NOT NULL,
  `balance_after` decimal(10,2) NOT NULL,
  `remark` varchar(200) DEFAULT NULL,
  `ref_id` int DEFAULT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_tenant` (`tenant_id`),
  KEY `idx_tenant_create_time` (`tenant_id`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 价格修改日志
CREATE TABLE IF NOT EXISTS `price_change_log` (
  `id` int NOT NULL AUTO_INCREMENT,
  `target_type` tinyint NOT NULL COMMENT '1档位 2商户',
  `target_id` int NOT NULL,
  `old_price` decimal(10,4) NOT NULL,
  `new_price` decimal(10,4) NOT NULL,
  `operator` varchar(50) NOT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 风控日志
CREATE TABLE IF NOT EXISTS `risk_log` (
  `id` int NOT NULL AUTO_INCREMENT,
  `line_id` int DEFAULT NULL,
  `tenant_id` int DEFAULT NULL,
  `phone` varchar(11) DEFAULT NULL,
  `risk_type` varchar(50) NOT NULL,
  `remark` varchar(200) DEFAULT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 系统通知
CREATE TABLE IF NOT EXISTS `system_notice` (
  `id` int NOT NULL AUTO_INCREMENT,
  `target_role` varchar(20) NOT NULL,
  `target_id` int DEFAULT NULL,
  `title` varchar(100) NOT NULL,
  `content` varchar(500) NOT NULL,
  `is_read` tinyint NOT NULL DEFAULT 0,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 日统计快照
CREATE TABLE IF NOT EXISTS `statistics_daily` (
  `id` int NOT NULL AUTO_INCREMENT,
  `stat_date` date NOT NULL,
  `tenant_id` int DEFAULT NULL,
  `line_id` int DEFAULT NULL,
  `total_calls` int NOT NULL DEFAULT 0,
  `connected_calls` int NOT NULL DEFAULT 0,
  `total_duration_sec` bigint NOT NULL DEFAULT 0,
  `total_deduct` decimal(12,4) NOT NULL DEFAULT 0,
  `total_cost` decimal(12,4) NOT NULL DEFAULT 0,
  `total_profit` decimal(12,4) NOT NULL DEFAULT 0,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_date_tenant_line` (`stat_date`, `tenant_id`, `line_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `statistics_monthly` (
  `id` int NOT NULL AUTO_INCREMENT,
  `stat_month` varchar(7) NOT NULL,
  `tenant_id` int DEFAULT NULL,
  `line_id` int DEFAULT NULL,
  `total_calls` int NOT NULL DEFAULT 0,
  `connected_calls` int NOT NULL DEFAULT 0,
  `total_duration_sec` bigint NOT NULL DEFAULT 0,
  `total_deduct` decimal(12,4) NOT NULL DEFAULT 0,
  `total_cost` decimal(12,4) NOT NULL DEFAULT 0,
  `total_profit` decimal(12,4) NOT NULL DEFAULT 0,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_month_tenant_line` (`stat_month`, `tenant_id`, `line_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
