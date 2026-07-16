-- MySQL dump 10.13  Distrib 8.0.31, for Win64 (x86_64)
--
-- Host: 127.0.0.1    Database: ai-call
-- ------------------------------------------------------
-- Server version	8.0.31

/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS */;
/*!40101 SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION */;
/*!50503 SET NAMES utf8mb4 */;
/*!40103 SET @OLD_TIME_ZONE=@@TIME_ZONE */;
/*!40103 SET TIME_ZONE='+00:00' */;
/*!40014 SET @OLD_UNIQUE_CHECKS=@@UNIQUE_CHECKS, UNIQUE_CHECKS=0 */;
/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;
/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;
/*!40111 SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0 */;

--
-- Current Database: `ai-call`
--

CREATE DATABASE /*!32312 IF NOT EXISTS*/ `ai-call` /*!40100 DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci */ /*!80016 DEFAULT ENCRYPTION='N' */;

USE `ai-call`;

--
-- Table structure for table `admin_user`
--

DROP TABLE IF EXISTS `admin_user`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `admin_user` (
  `id` int NOT NULL AUTO_INCREMENT COMMENT '主键',
  `username` varchar(50) NOT NULL COMMENT '登录用户名',
  `password` varchar(100) NOT NULL COMMENT '密码（MD5）',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_username` (`username`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=3 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC COMMENT='总后台管理员账号';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `ai_model_config`
--

DROP TABLE IF EXISTS `ai_model_config`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ai_model_config` (
  `id` int NOT NULL AUTO_INCREMENT COMMENT '主键',
  `config_name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '配置名称',
  `provider` varchar(20) NOT NULL COMMENT '提供商：ollama|qwen|wenxin',
  `base_url` varchar(300) NOT NULL DEFAULT '' COMMENT 'API基础地址',
  `model_name` varchar(100) NOT NULL DEFAULT '' COMMENT '模型名称',
  `api_key` varchar(500) DEFAULT '' COMMENT 'API Key',
  `secret_key` varchar(500) DEFAULT '' COMMENT 'Secret Key（文心等）',
  `max_tokens` int NOT NULL DEFAULT '80' COMMENT '最大生成token数',
  `temperature` decimal(3,2) NOT NULL DEFAULT '0.70' COMMENT '温度参数',
  `max_history_rounds` int NOT NULL DEFAULT '3' COMMENT '携带历史对话轮数',
  `connect_timeout_ms` int NOT NULL DEFAULT '5000' COMMENT '连接超时（毫秒）',
  `read_timeout_ms` int NOT NULL DEFAULT '60000' COMMENT '读取超时（毫秒）',
  `is_active` tinyint NOT NULL DEFAULT '0' COMMENT '是否当前启用：1是 0否',
  `remark` varchar(200) DEFAULT '' COMMENT '备注',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=5 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC COMMENT='大模型连接配置';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `ai_prompt`
--

DROP TABLE IF EXISTS `ai_prompt`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ai_prompt` (
  `id` int NOT NULL AUTO_INCREMENT COMMENT '主键',
  `prompt_name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '模板名称',
  `prompt_content` text NOT NULL COMMENT '系统提示词/角色设定',
  `opening_remarks` varchar(200) NOT NULL COMMENT '开场白文案',
  `end_remarks` varchar(100) NOT NULL COMMENT '结束语文案',
  `opening_wav_path` varchar(500) DEFAULT NULL COMMENT '开场白录音路径',
  `ending_wav_path` varchar(500) DEFAULT NULL COMMENT '结束语录音路径',
  `kb_id` int DEFAULT NULL COMMENT '绑定的训练知识库ID',
  `is_active` tinyint NOT NULL DEFAULT '1' COMMENT '是否启用：1是 0否',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=2 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC COMMENT='AI话术模板';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `balance_log`
--

DROP TABLE IF EXISTS `balance_log`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `balance_log` (
  `id` int NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` int NOT NULL COMMENT '商户ID',
  `type` tinyint NOT NULL COMMENT '类型：1充值 2通话扣费 3补扣',
  `amount` decimal(10,4) NOT NULL COMMENT '变动金额',
  `balance_after` decimal(10,2) NOT NULL COMMENT '变动后余额',
  `remark` varchar(200) DEFAULT NULL COMMENT '备注',
  `ref_id` int DEFAULT NULL COMMENT '关联业务ID',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_tenant` (`tenant_id`) USING BTREE,
  KEY `idx_tenant_create_time` (`tenant_id`,`create_time`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=163 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC COMMENT='商户余额变动明细';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `call_background_scene`
--

DROP TABLE IF EXISTS `call_background_scene`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `call_background_scene` (
  `id` int NOT NULL AUTO_INCREMENT,
  `scene_name` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `bg_type` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT 'custom' COMMENT 'office|quiet|room|callcenter|custom',
  `audio_url` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `volume` double DEFAULT '0.22',
  `status` tinyint DEFAULT '1',
  `remark` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL,
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=5 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC COMMENT='外呼通话背景场景音';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `call_record`
--

DROP TABLE IF EXISTS `call_record`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `call_record` (
  `id` int NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` int NOT NULL COMMENT '商户ID',
  `line_id` int NOT NULL COMMENT '使用线路ID',
  `customer_id` int DEFAULT NULL COMMENT '客户ID',
  `customer_phone` varchar(11) NOT NULL COMMENT '客户手机号',
  `call_duration` int NOT NULL DEFAULT '0' COMMENT '通话时长（秒）',
  `billed_minutes` int NOT NULL DEFAULT '0' COMMENT '计费分钟数',
  `prepaid_amount` decimal(10,4) NOT NULL DEFAULT '0.0000' COMMENT '通话中已实时预扣金额',
  `sell_price_snapshot` decimal(10,4) DEFAULT NULL COMMENT '通话时销售单价快照',
  `cost_price_snapshot` decimal(10,4) DEFAULT NULL COMMENT '通话时线路成本快照',
  `deduct_amount` decimal(10,4) NOT NULL DEFAULT '0.0000' COMMENT '实际扣费金额',
  `cost_amount` decimal(10,4) NOT NULL DEFAULT '0.0000' COMMENT '线路成本金额',
  `profit` decimal(10,4) NOT NULL DEFAULT '0.0000' COMMENT '毛利',
  `profit_abnormal` tinyint NOT NULL DEFAULT '0' COMMENT '是否负毛利：1是 0否',
  `record_url` varchar(200) DEFAULT '' COMMENT '全程录音URL',
  `dialog_text` text COMMENT '人机对话文本',
  `customer_need` varchar(200) DEFAULT NULL COMMENT '客户需求（AI提取）',
  `customer_pain` varchar(200) DEFAULT NULL COMMENT '客户痛点（AI提取）',
  `budget` varchar(100) DEFAULT NULL COMMENT '预算（AI提取）',
  `next_time` varchar(50) DEFAULT NULL COMMENT '建议回访时间',
  `level` varchar(10) NOT NULL DEFAULT 'D' COMMENT '意向等级 A/B/C/D',
  `call_status` tinyint NOT NULL COMMENT '通话状态：1接通 2无人 3空号 4拒接 5失败',
  `hangup_type` varchar(50) DEFAULT NULL COMMENT '挂断类型：正常结束/转人工/强制挂断等',
  `call_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `task_id` int DEFAULT NULL COMMENT '外呼任务ID',
  `prompt_id` int DEFAULT NULL COMMENT '通话快照-话术模板ID',
  `kb_id` int DEFAULT NULL COMMENT '通话快照-知识库ID',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_tenant` (`tenant_id`) USING BTREE,
  KEY `idx_call_time` (`call_time`) USING BTREE,
  KEY `idx_tenant_call_time` (`tenant_id`,`call_time`) USING BTREE,
  KEY `idx_tenant_phone_id` (`tenant_id`,`customer_phone`,`id`) USING BTREE,
  KEY `idx_profit_abnormal` (`profit_abnormal`,`call_time`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=323 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC COMMENT='通话记录与计费';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `call_task`
--

DROP TABLE IF EXISTS `call_task`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `call_task` (
  `id` int NOT NULL AUTO_INCREMENT COMMENT '主键',
  `task_name` varchar(100) NOT NULL COMMENT '任务名称',
  `tenant_id` int NOT NULL COMMENT '所属商户ID',
  `prompt_id` int DEFAULT NULL COMMENT '话术模板ID（空则继承商户）',
  `group_id` int NOT NULL COMMENT '目标客户分组ID',
  `call_count` int NOT NULL DEFAULT '0' COMMENT '计划外呼客户数',
  `completed_count` int NOT NULL DEFAULT '0' COMMENT '已完成外呼数',
  `success_count` int NOT NULL DEFAULT '0' COMMENT '接通成功数',
  `status` tinyint NOT NULL DEFAULT '0' COMMENT '状态：0未启动 1运行 2暂停 3完成 4终止',
  `dial_mode` tinyint NOT NULL DEFAULT '1' COMMENT '拨号模式：1立即 2定时',
  `scheduled_start_time` datetime DEFAULT NULL COMMENT '定时外呼开始时间',
  `max_ring_count` int NOT NULL DEFAULT '10' COMMENT '最大振铃次数',
  `task_rules` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '外呼规则说明',
  `dialog_pipeline_mode` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT 'turn-based|omni-realtime|null=跟随全局',
  `silence_profile` varchar(16) DEFAULT NULL COMMENT '句末静音档位：stable|fast|null=跟随全局',
  `background_scene_id` int DEFAULT NULL COMMENT '通话背景场景ID，null=不使用',
  `auto_add_wechat` tinyint NOT NULL DEFAULT '0' COMMENT '接通后是否自动加微信',
  `wechat_add_api_url` varchar(255) DEFAULT NULL COMMENT '加微信接口地址',
  `wechat_add_message` varchar(500) DEFAULT NULL COMMENT '加好友招呼语模板',
  `wechat_add_remark` varchar(200) DEFAULT NULL COMMENT '加好友备注模板',
  `start_time` datetime DEFAULT NULL COMMENT '任务实际开始时间',
  `end_time` datetime DEFAULT NULL COMMENT '任务结束时间',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_tenant` (`tenant_id`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=98 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC COMMENT='外呼任务';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `customer`
--

DROP TABLE IF EXISTS `customer`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `customer` (
  `id` int NOT NULL AUTO_INCREMENT COMMENT '主键',
  `phone` varchar(11) NOT NULL COMMENT '客户手机号',
  `name` varchar(30) DEFAULT NULL COMMENT '客户姓名',
  `province` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '省份',
  `group_id` int NOT NULL DEFAULT '1' COMMENT '分组ID',
  `group_name` varchar(50) NOT NULL DEFAULT '未分组' COMMENT '分组名称（冗余）',
  `level` varchar(10) DEFAULT NULL COMMENT '意向等级 A/B/C/D',
  `is_black` tinyint NOT NULL DEFAULT '0' COMMENT '是否黑名单：1是 0否',
  `tenant_id` int NOT NULL COMMENT '所属商户ID',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `last_call_time` datetime DEFAULT NULL COMMENT '最近外呼时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_phone_tenant` (`phone`,`tenant_id`) USING BTREE,
  KEY `idx_tenant` (`tenant_id`) USING BTREE,
  KEY `idx_tenant_level` (`tenant_id`,`level`,`last_call_time`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=8 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC COMMENT='外呼客户名单';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `customer_group`
--

DROP TABLE IF EXISTS `customer_group`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `customer_group` (
  `id` int NOT NULL AUTO_INCREMENT COMMENT '主键',
  `group_name` varchar(50) NOT NULL COMMENT '分组名称',
  `tenant_id` int NOT NULL COMMENT '所属商户ID',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_tenant` (`tenant_id`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=9 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC COMMENT='客户分组';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `dialog_knowledge_base`
--

DROP TABLE IF EXISTS `dialog_knowledge_base`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `dialog_knowledge_base` (
  `id` int NOT NULL AUTO_INCREMENT COMMENT '主键',
  `kb_name` varchar(100) NOT NULL COMMENT '知识库名称',
  `description` varchar(500) DEFAULT NULL COMMENT '描述',
  `tenant_id` int DEFAULT NULL COMMENT '所属商户ID（空为平台级）',
  `pack_type` varchar(32) NOT NULL DEFAULT 'custom' COMMENT '话术包类型：loan/custom等',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '状态：1启用 0停用',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_tenant_status` (`tenant_id`,`status`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=2 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC COMMENT='对话知识库';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `dialog_training_qa`
--

DROP TABLE IF EXISTS `dialog_training_qa`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `dialog_training_qa` (
  `id` int NOT NULL AUTO_INCREMENT COMMENT '主键',
  `kb_id` int NOT NULL DEFAULT '1' COMMENT '所属知识库ID',
  `question` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '用户问题/意图',
  `standard_answer` varchar(2000) NOT NULL COMMENT '标准回复话术',
  `answer_wav_path` varchar(500) DEFAULT NULL COMMENT '标准答录音路径（智能预录外呼播放）',
  `data_type` tinyint NOT NULL DEFAULT '1' COMMENT '数据类型：1人工修正 2优质样本 3负样本',
  `weight` decimal(4,2) NOT NULL DEFAULT '1.00' COMMENT 'RAG检索权重加成',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '状态：1启用 0停用',
  `source_call_id` int DEFAULT NULL COMMENT '来源通话记录ID',
  `remark` varchar(200) DEFAULT NULL COMMENT '备注',
  `flow_order` int DEFAULT NULL COMMENT '主线节点排序',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_status_type` (`status`,`data_type`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=354 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC COMMENT='对话训练标准问答（RAG）';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `global_blacklist`
--

DROP TABLE IF EXISTS `global_blacklist`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `global_blacklist` (
  `id` int NOT NULL AUTO_INCREMENT COMMENT '主键',
  `phone` varchar(11) NOT NULL COMMENT '黑名单手机号',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_phone` (`phone`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC COMMENT='全局号码黑名单';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `line`
--

DROP TABLE IF EXISTS `line`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `line` (
  `id` int NOT NULL AUTO_INCREMENT COMMENT '主键',
  `sip_account` varchar(50) NOT NULL COMMENT 'SIP账号/网关名',
  `sip_password` varchar(50) NOT NULL COMMENT 'SIP密码',
  `sip_address` varchar(100) NOT NULL COMMENT 'SIP服务器地址 host:port',
  `cost_price` decimal(10,4) NOT NULL DEFAULT '0.0600' COMMENT '线路成本单价（元/分钟）',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '状态：0禁用 1启用',
  `daily_call_limit` int NOT NULL DEFAULT '1000' COMMENT '日呼叫上限',
  `current_concurrent` int NOT NULL DEFAULT '0' COMMENT '当前并发数',
  `today_call_count` int NOT NULL DEFAULT '0' COMMENT '今日已呼次数',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_sip_account` (`sip_account`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=3 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC COMMENT='SIP外呼线路';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `prerecord_audio_clip`
--

DROP TABLE IF EXISTS `prerecord_audio_clip`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `prerecord_audio_clip` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `faq_id` bigint DEFAULT NULL COMMENT '关联FAQ ID，null为全局片段',
  `clip_type` varchar(24) NOT NULL COMMENT '片段类型：buffer/answer/closing/transfer等',
  `text_content` varchar(512) NOT NULL COMMENT '对应文案',
  `wav_path` varchar(512) DEFAULT NULL COMMENT '8k电话wav文件路径',
  `variant_no` int NOT NULL DEFAULT '1' COMMENT '同类型变体序号',
  `enabled` tinyint NOT NULL DEFAULT '1' COMMENT '是否启用：1是 0否',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_prerecord_clip_faq` (`faq_id`,`clip_type`)
) ENGINE=InnoDB AUTO_INCREMENT=33 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='智能预录外呼音频片段';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `prerecord_faq`
--

DROP TABLE IF EXISTS `prerecord_faq`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `prerecord_faq` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `question_display` varchar(256) NOT NULL COMMENT '展示用客户原话',
  `question_norm` varchar(256) NOT NULL COMMENT '规范化问题（匹配键）',
  `category` varchar(32) NOT NULL DEFAULT '其他' COMMENT '问题分类：利率/额度/征信等',
  `keywords` varchar(512) DEFAULT NULL COMMENT '逗号分隔匹配关键词',
  `answer_text` varchar(512) DEFAULT NULL COMMENT '标准应答文案',
  `tier` varchar(24) NOT NULL DEFAULT 'high_freq' COMMENT '层级：high_freq高频 cold冷门',
  `hit_count` int NOT NULL DEFAULT '0' COMMENT '历史挖掘出现频次',
  `match_count` int NOT NULL DEFAULT '0' COMMENT '运行时匹配次数',
  `transfer_count` int NOT NULL DEFAULT '0' COMMENT '转顾问次数',
  `enabled` tinyint NOT NULL DEFAULT '1' COMMENT '是否启用：1是 0否',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_prerecord_faq_norm` (`question_norm`),
  KEY `idx_prerecord_faq_tier` (`tier`)
) ENGINE=InnoDB AUTO_INCREMENT=17 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='智能预录外呼FAQ问题库';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `price_change_log`
--

DROP TABLE IF EXISTS `price_change_log`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `price_change_log` (
  `id` int NOT NULL AUTO_INCREMENT COMMENT '主键',
  `target_type` tinyint NOT NULL COMMENT '对象类型：1费率档位 2商户',
  `target_id` int NOT NULL COMMENT '对象ID',
  `old_price` decimal(10,4) NOT NULL COMMENT '修改前单价',
  `new_price` decimal(10,4) NOT NULL COMMENT '修改后单价',
  `operator` varchar(50) NOT NULL COMMENT '操作人',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC COMMENT='售价修改审计日志';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `price_config`
--

DROP TABLE IF EXISTS `price_config`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `price_config` (
  `id` int NOT NULL AUTO_INCREMENT COMMENT '主键',
  `price_type` tinyint NOT NULL COMMENT '费率类型：1零售 2企业 3代理',
  `default_sell_price` decimal(10,4) NOT NULL COMMENT '该档位默认销售单价（元/分钟）',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_price_type` (`price_type`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=4 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC COMMENT='费率档位默认售价配置';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `recharge_order`
--

DROP TABLE IF EXISTS `recharge_order`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `recharge_order` (
  `id` int NOT NULL AUTO_INCREMENT COMMENT '主键',
  `order_no` varchar(50) NOT NULL COMMENT '充值订单号',
  `tenant_id` int NOT NULL COMMENT '商户ID',
  `recharge_amount` decimal(10,2) NOT NULL COMMENT '充值金额（元）',
  `arrival_balance` decimal(10,2) DEFAULT NULL COMMENT '到账后余额',
  `status` tinyint NOT NULL DEFAULT '0' COMMENT '状态：0待审核 1已到账 2失败',
  `voucher_url` varchar(200) DEFAULT NULL COMMENT '转账凭证图片URL',
  `fail_reason` varchar(200) DEFAULT NULL COMMENT '审核失败原因',
  `recharge_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `audit_time` datetime DEFAULT NULL COMMENT '审核时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_order_no` (`order_no`) USING BTREE,
  KEY `idx_tenant` (`tenant_id`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=2 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC COMMENT='商户充值订单';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `risk_config`
--

DROP TABLE IF EXISTS `risk_config`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `risk_config` (
  `id` int NOT NULL AUTO_INCREMENT COMMENT '主键（固定为1）',
  `call_interval` int NOT NULL DEFAULT '30' COMMENT '外呼间隔（秒）',
  `short_call_limit` int NOT NULL DEFAULT '15' COMMENT '短通话判定阈值（秒）',
  `call_start_time` varchar(5) NOT NULL DEFAULT '09:00' COMMENT '允许外呼开始时间 HH:mm',
  `call_end_time` varchar(5) NOT NULL DEFAULT '19:00' COMMENT '允许外呼结束时间 HH:mm',
  `high_complaint_area` varchar(200) DEFAULT NULL COMMENT '高投诉地区（逗号分隔）',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `sensitive_monitor_enabled` tinyint NOT NULL DEFAULT '1' COMMENT '是否启用客户话术敏感词监控',
  `human_transfer_enabled` tinyint NOT NULL DEFAULT '0' COMMENT '命中敏感词是否转人工',
  `human_transfer_dest` varchar(200) DEFAULT NULL COMMENT 'FS转接目标（user/分机 或 sofia/gateway/...）',
  `human_transfer_prompt` varchar(200) DEFAULT '检测到需要人工协助，正在为您转接，请稍候。' COMMENT '转人工前对客户播报话术',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=2 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC COMMENT='全局风控与转人工配置';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `risk_log`
--

DROP TABLE IF EXISTS `risk_log`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `risk_log` (
  `id` int NOT NULL AUTO_INCREMENT COMMENT '主键',
  `line_id` int DEFAULT NULL COMMENT '线路ID',
  `tenant_id` int DEFAULT NULL COMMENT '商户ID',
  `phone` varchar(11) DEFAULT NULL COMMENT '相关手机号',
  `risk_type` varchar(50) NOT NULL COMMENT '风险类型',
  `remark` varchar(200) DEFAULT NULL COMMENT '备注说明',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC COMMENT='风控事件日志';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `sensitive_word`
--

DROP TABLE IF EXISTS `sensitive_word`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sensitive_word` (
  `id` int NOT NULL AUTO_INCREMENT COMMENT '主键',
  `word` varchar(64) NOT NULL COMMENT '敏感词/违规词',
  `word_type` tinyint NOT NULL DEFAULT '1' COMMENT '类型：1违规 2敏感',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '状态：1启用 0停用',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_word` (`word`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=2255 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC COMMENT='敏感词/违规词库';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `statistics_daily`
--

DROP TABLE IF EXISTS `statistics_daily`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `statistics_daily` (
  `id` int NOT NULL AUTO_INCREMENT COMMENT '主键',
  `stat_date` date NOT NULL COMMENT '统计日期',
  `tenant_id` int DEFAULT NULL COMMENT '商户ID（空为全平台）',
  `line_id` int DEFAULT NULL COMMENT '线路ID（空为汇总）',
  `total_calls` int NOT NULL DEFAULT '0' COMMENT '总呼叫次数',
  `connected_calls` int NOT NULL DEFAULT '0' COMMENT '接通次数',
  `total_duration_sec` bigint NOT NULL DEFAULT '0' COMMENT '总通话时长（秒）',
  `total_deduct` decimal(12,4) NOT NULL DEFAULT '0.0000' COMMENT '总扣费',
  `total_cost` decimal(12,4) NOT NULL DEFAULT '0.0000' COMMENT '总成本',
  `total_profit` decimal(12,4) NOT NULL DEFAULT '0.0000' COMMENT '总毛利',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_date_tenant_line` (`stat_date`,`tenant_id`,`line_id`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=47 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC COMMENT='日统计快照';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `statistics_monthly`
--

DROP TABLE IF EXISTS `statistics_monthly`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `statistics_monthly` (
  `id` int NOT NULL AUTO_INCREMENT COMMENT '主键',
  `stat_month` varchar(7) NOT NULL COMMENT '统计月份 yyyy-MM',
  `tenant_id` int DEFAULT NULL COMMENT '商户ID（空为全平台）',
  `line_id` int DEFAULT NULL COMMENT '线路ID（空为汇总）',
  `total_calls` int NOT NULL DEFAULT '0' COMMENT '总呼叫次数',
  `connected_calls` int NOT NULL DEFAULT '0' COMMENT '接通次数',
  `total_duration_sec` bigint NOT NULL DEFAULT '0' COMMENT '总通话时长（秒）',
  `total_deduct` decimal(12,4) NOT NULL DEFAULT '0.0000' COMMENT '总扣费',
  `total_cost` decimal(12,4) NOT NULL DEFAULT '0.0000' COMMENT '总成本',
  `total_profit` decimal(12,4) NOT NULL DEFAULT '0.0000' COMMENT '总毛利',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_month_tenant_line` (`stat_month`,`tenant_id`,`line_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC COMMENT='月统计快照';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `system_notice`
--

DROP TABLE IF EXISTS `system_notice`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `system_notice` (
  `id` int NOT NULL AUTO_INCREMENT COMMENT '主键',
  `target_role` varchar(20) NOT NULL COMMENT '目标角色：admin/tenant',
  `target_id` int DEFAULT NULL COMMENT '目标用户ID（空为全体）',
  `title` varchar(100) NOT NULL COMMENT '通知标题',
  `content` varchar(500) NOT NULL COMMENT '通知内容',
  `is_read` tinyint NOT NULL DEFAULT '0' COMMENT '是否已读：1是 0否',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=2 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC COMMENT='系统通知';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `tenant`
--

DROP TABLE IF EXISTS `tenant`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `tenant` (
  `id` int NOT NULL AUTO_INCREMENT COMMENT '主键',
  `username` varchar(50) NOT NULL COMMENT '商户登录名',
  `password` varchar(100) NOT NULL COMMENT '密码（MD5）',
  `contact_name` varchar(30) NOT NULL COMMENT '联系人姓名',
  `contact_phone` varchar(11) NOT NULL COMMENT '联系人手机',
  `balance` decimal(10,2) NOT NULL DEFAULT '0.00' COMMENT '账户余额（元）',
  `pending_deduct` decimal(10,4) NOT NULL DEFAULT '0.0000' COMMENT '待补扣金额',
  `price_type` tinyint NOT NULL DEFAULT '1' COMMENT '费率档位：1零售 2企业 3代理',
  `sell_price` decimal(10,4) NOT NULL DEFAULT '0.1500' COMMENT '销售单价（元/分钟）',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '状态：0禁用 1正常',
  `daily_call_limit` int NOT NULL DEFAULT '500' COMMENT '日外呼上限',
  `prompt_id` int DEFAULT NULL COMMENT '默认话术模板ID',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `last_login_time` datetime DEFAULT NULL COMMENT '最近登录时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_username` (`username`) USING BTREE,
  UNIQUE KEY `uk_contact_phone` (`contact_phone`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=2 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC COMMENT='商户/租户';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `voice_runtime_config`
--

DROP TABLE IF EXISTS `voice_runtime_config`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `voice_runtime_config` (
  `id` int NOT NULL COMMENT '主键（固定为1）',
  `dialog_pipeline_mode` varchar(32) NOT NULL DEFAULT 'turn-based' COMMENT '对话管线模式（legacy，固定turn-based）',
  `silence_profile` varchar(16) NOT NULL DEFAULT 'stable' COMMENT '句末静音档位：stable|fast',
  `omni_realtime_voice` varchar(128) NOT NULL DEFAULT 'Ethan' COMMENT 'Omni实时音色（legacy）',
  `cosyvoice_clone_voice_id` varchar(128) DEFAULT NULL COMMENT 'CosyVoice复刻音色voice_id',
  `tts_voice_mode` varchar(16) NOT NULL DEFAULT 'clone' COMMENT 'TTS音色来源：clone|system',
  `cosyvoice_system_voice` varchar(64) DEFAULT 'longanyang' COMMENT 'CosyVoice系统预置音色',
  `play_opening_on_answer` tinyint NOT NULL DEFAULT '1' COMMENT '接通后是否播报开场白',
  `omni_play_traditional_opening` tinyint NOT NULL DEFAULT '0' COMMENT '是否播传统开场（legacy）',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `outbound_dialog_mode` varchar(32) NOT NULL DEFAULT 'ai_realtime' COMMENT '外呼对话模式：ai_realtime|smart_prerecord',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC COMMENT='外呼语音运行时配置（全局单行）';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping routines for database 'ai-call'
--
/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;

-- Dump completed on 2026-06-30 22:09:12
