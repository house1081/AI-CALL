package com.aicall.config;

import java.util.LinkedHashMap;
import java.util.Map;

/** 数据库表/字段中文注释定义 */
public final class TableCommentCatalog {

    private TableCommentCatalog() {
    }

    public static Map<String, String> tableComments() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("admin_user", "总后台管理员账号");
        m.put("price_config", "费率档位默认售价配置");
        m.put("line", "SIP外呼线路");
        m.put("tenant", "商户/租户");
        m.put("risk_config", "全局风控与转人工配置");
        m.put("global_blacklist", "全局号码黑名单");
        m.put("ai_prompt", "AI话术模板");
        m.put("ai_model_config", "大模型连接配置");
        m.put("customer_group", "客户分组");
        m.put("customer", "外呼客户名单");
        m.put("call_task", "外呼任务");
        m.put("call_record", "通话记录与计费");
        m.put("recharge_order", "商户充值订单");
        m.put("balance_log", "商户余额变动明细");
        m.put("price_change_log", "售价修改审计日志");
        m.put("risk_log", "风控事件日志");
        m.put("system_notice", "系统通知");
        m.put("statistics_daily", "日统计快照");
        m.put("statistics_monthly", "月统计快照");
        m.put("sensitive_word", "敏感词/违规词库");
        m.put("dialog_training_qa", "对话训练标准问答（RAG）");
        m.put("dialog_knowledge_base", "对话知识库");
        m.put("voice_runtime_config", "外呼语音运行时配置（全局单行）");
        m.put("prerecord_faq", "智能预录外呼FAQ问题库");
        m.put("prerecord_audio_clip", "智能预录外呼音频片段");
        return m;
    }

    public static Map<String, Map<String, String>> columnComments() {
        Map<String, Map<String, String>> all = new LinkedHashMap<>();
        cols(all, "admin_user", map(
                "id", "主键",
                "username", "登录用户名",
                "password", "密码（MD5）",
                "create_time", "创建时间"));
        cols(all, "price_config", map(
                "id", "主键",
                "price_type", "费率类型：1零售 2企业 3代理",
                "default_sell_price", "该档位默认销售单价（元/分钟）",
                "update_time", "更新时间"));
        cols(all, "line", map(
                "id", "主键",
                "sip_account", "SIP账号/网关名",
                "sip_password", "SIP密码",
                "sip_address", "SIP服务器地址 host:port",
                "cost_price", "线路成本单价（元/分钟）",
                "status", "状态：0禁用 1启用",
                "daily_call_limit", "日呼叫上限",
                "current_concurrent", "当前并发数",
                "today_call_count", "今日已呼次数",
                "create_time", "创建时间"));
        cols(all, "tenant", map(
                "id", "主键",
                "username", "商户登录名",
                "password", "密码（MD5）",
                "contact_name", "联系人姓名",
                "contact_phone", "联系人手机",
                "balance", "账户余额（元）",
                "pending_deduct", "待补扣金额",
                "price_type", "费率档位：1零售 2企业 3代理",
                "sell_price", "销售单价（元/分钟）",
                "status", "状态：0禁用 1正常",
                "daily_call_limit", "日外呼上限",
                "prompt_id", "默认话术模板ID",
                "create_time", "注册时间",
                "last_login_time", "最近登录时间"));
        cols(all, "risk_config", map(
                "id", "主键（固定为1）",
                "call_interval", "外呼间隔（秒）",
                "short_call_limit", "短通话判定阈值（秒）",
                "call_start_time", "允许外呼开始时间 HH:mm",
                "call_end_time", "允许外呼结束时间 HH:mm",
                "high_complaint_area", "高投诉地区（逗号分隔）",
                "sensitive_monitor_enabled", "是否启用客户话术敏感词监控",
                "human_transfer_enabled", "命中敏感词是否转人工",
                "human_transfer_dest", "FS转接目标（user/分机 或 sofia/gateway/...）",
                "human_transfer_prompt", "转人工前对客户播报话术",
                "update_time", "更新时间"));
        cols(all, "global_blacklist", map(
                "id", "主键",
                "phone", "黑名单手机号",
                "create_time", "加入时间"));
        cols(all, "ai_prompt", map(
                "id", "主键",
                "prompt_name", "模板名称",
                "prompt_content", "系统提示词/角色设定",
                "opening_remarks", "开场白文案",
                "end_remarks", "结束语文案",
                "kb_id", "绑定的训练知识库ID",
                "is_active", "是否启用：1是 0否",
                "update_time", "更新时间"));
        cols(all, "ai_model_config", map(
                "id", "主键",
                "config_name", "配置名称",
                "provider", "提供商：ollama|qwen|wenxin",
                "base_url", "API基础地址",
                "model_name", "模型名称",
                "api_key", "API Key",
                "secret_key", "Secret Key（文心等）",
                "max_tokens", "最大生成token数",
                "temperature", "温度参数",
                "max_history_rounds", "携带历史对话轮数",
                "connect_timeout_ms", "连接超时（毫秒）",
                "read_timeout_ms", "读取超时（毫秒）",
                "is_active", "是否当前启用：1是 0否",
                "remark", "备注",
                "update_time", "更新时间"));
        cols(all, "customer_group", map(
                "id", "主键",
                "group_name", "分组名称",
                "tenant_id", "所属商户ID",
                "create_time", "创建时间"));
        cols(all, "customer", map(
                "id", "主键",
                "phone", "客户手机号",
                "name", "客户姓名",
                "province", "省份",
                "group_id", "分组ID",
                "group_name", "分组名称（冗余）",
                "level", "意向等级 A/B/C/D",
                "is_black", "是否黑名单：1是 0否",
                "tenant_id", "所属商户ID",
                "create_time", "导入时间",
                "last_call_time", "最近外呼时间"));
        cols(all, "call_task", map(
                "id", "主键",
                "task_name", "任务名称",
                "tenant_id", "所属商户ID",
                "prompt_id", "话术模板ID（空则继承商户）",
                "group_id", "目标客户分组ID",
                "call_count", "计划外呼客户数",
                "completed_count", "已完成外呼数",
                "success_count", "接通成功数",
                "status", "状态：0未启动 1运行 2暂停 3完成 4终止",
                "dial_mode", "拨号模式：1立即 2定时",
                "scheduled_start_time", "定时外呼开始时间",
                "max_ring_count", "最大振铃次数",
                "task_rules", "外呼规则说明",
                "silence_profile", "句末静音档位：stable|fast|null=跟随全局",
                "auto_add_wechat", "接通后是否自动加微信",
                "wechat_add_api_url", "加微信接口地址",
                "wechat_add_message", "加好友招呼语模板",
                "wechat_add_remark", "加好友备注模板",
                "start_time", "任务实际开始时间",
                "end_time", "任务结束时间",
                "create_time", "创建时间"));
        cols(all, "call_record", map(
                "id", "主键",
                "tenant_id", "商户ID",
                "line_id", "使用线路ID",
                "customer_id", "客户ID",
                "customer_phone", "客户手机号",
                "call_duration", "通话时长（秒）",
                "billed_minutes", "计费分钟数",
                "prepaid_amount", "通话中已实时预扣金额",
                "sell_price_snapshot", "通话时销售单价快照",
                "cost_price_snapshot", "通话时线路成本快照",
                "deduct_amount", "实际扣费金额",
                "cost_amount", "线路成本金额",
                "profit", "毛利",
                "profit_abnormal", "是否负毛利：1是 0否",
                "record_url", "全程录音URL",
                "dialog_text", "人机对话文本",
                "customer_need", "客户需求（AI提取）",
                "customer_pain", "客户痛点（AI提取）",
                "budget", "预算（AI提取）",
                "next_time", "建议回访时间",
                "level", "意向等级 A/B/C/D",
                "call_status", "通话状态：1接通 2无人 3空号 4拒接 5失败",
                "hangup_type", "挂断类型：正常结束/转人工/强制挂断等",
                "call_time", "通话开始时间",
                "task_id", "外呼任务ID",
                "prompt_id", "通话快照-话术模板ID",
                "kb_id", "通话快照-知识库ID"));
        cols(all, "recharge_order", map(
                "id", "主键",
                "order_no", "充值订单号",
                "tenant_id", "商户ID",
                "recharge_amount", "充值金额（元）",
                "arrival_balance", "到账后余额",
                "status", "状态：0待审核 1已到账 2失败",
                "voucher_url", "转账凭证图片URL",
                "fail_reason", "审核失败原因",
                "recharge_time", "提交时间",
                "audit_time", "审核时间"));
        cols(all, "balance_log", map(
                "id", "主键",
                "tenant_id", "商户ID",
                "type", "类型：1充值 2通话扣费 3补扣",
                "amount", "变动金额",
                "balance_after", "变动后余额",
                "remark", "备注",
                "ref_id", "关联业务ID",
                "create_time", "创建时间"));
        cols(all, "price_change_log", map(
                "id", "主键",
                "target_type", "对象类型：1费率档位 2商户",
                "target_id", "对象ID",
                "old_price", "修改前单价",
                "new_price", "修改后单价",
                "operator", "操作人",
                "create_time", "操作时间"));
        cols(all, "risk_log", map(
                "id", "主键",
                "line_id", "线路ID",
                "tenant_id", "商户ID",
                "phone", "相关手机号",
                "risk_type", "风险类型",
                "remark", "备注说明",
                "create_time", "记录时间"));
        cols(all, "system_notice", map(
                "id", "主键",
                "target_role", "目标角色：admin/tenant",
                "target_id", "目标用户ID（空为全体）",
                "title", "通知标题",
                "content", "通知内容",
                "is_read", "是否已读：1是 0否",
                "create_time", "创建时间"));
        cols(all, "statistics_daily", map(
                "id", "主键",
                "stat_date", "统计日期",
                "tenant_id", "商户ID（空为全平台）",
                "line_id", "线路ID（空为汇总）",
                "total_calls", "总呼叫次数",
                "connected_calls", "接通次数",
                "total_duration_sec", "总通话时长（秒）",
                "total_deduct", "总扣费",
                "total_cost", "总成本",
                "total_profit", "总毛利",
                "create_time", "快照生成时间"));
        cols(all, "statistics_monthly", map(
                "id", "主键",
                "stat_month", "统计月份 yyyy-MM",
                "tenant_id", "商户ID（空为全平台）",
                "line_id", "线路ID（空为汇总）",
                "total_calls", "总呼叫次数",
                "connected_calls", "接通次数",
                "total_duration_sec", "总通话时长（秒）",
                "total_deduct", "总扣费",
                "total_cost", "总成本",
                "total_profit", "总毛利",
                "create_time", "快照生成时间"));
        cols(all, "sensitive_word", map(
                "id", "主键",
                "word", "敏感词/违规词",
                "word_type", "类型：1违规 2敏感",
                "status", "状态：1启用 0停用",
                "create_time", "创建时间"));
        cols(all, "dialog_training_qa", map(
                "id", "主键",
                "kb_id", "所属知识库ID",
                "question", "用户问题/意图",
                "standard_answer", "标准回复话术",
                "data_type", "数据类型：1人工修正 2优质样本 3负样本",
                "weight", "RAG检索权重加成",
                "status", "状态：1启用 0停用",
                "source_call_id", "来源通话记录ID",
                "remark", "备注",
                "create_time", "创建时间",
                "update_time", "更新时间"));
        cols(all, "dialog_knowledge_base", map(
                "id", "主键",
                "kb_name", "知识库名称",
                "description", "描述",
                "tenant_id", "所属商户ID（空为平台级）",
                "pack_type", "话术包类型：loan/custom等",
                "status", "状态：1启用 0停用",
                "create_time", "创建时间",
                "update_time", "更新时间"));
        cols(all, "voice_runtime_config", map(
                "id", "主键（固定为1）",
                "dialog_pipeline_mode", "对话管线模式（legacy，固定turn-based）",
                "omni_realtime_voice", "Omni实时音色（legacy）",
                "omni_play_traditional_opening", "是否播传统开场（legacy）",
                "play_opening_on_answer", "接通后是否播报开场白",
                "cosyvoice_clone_voice_id", "CosyVoice复刻音色voice_id",
                "tts_voice_mode", "TTS音色来源：clone|system",
                "cosyvoice_system_voice", "CosyVoice系统预置音色",
                "silence_profile", "句末静音档位：stable|fast",
                "outbound_dialog_mode", "外呼对话模式：ai_realtime|smart_prerecord",
                "update_time", "更新时间"));
        cols(all, "prerecord_faq", map(
                "id", "主键",
                "question_display", "展示用客户原话",
                "question_norm", "规范化问题（匹配键）",
                "category", "问题分类：利率/额度/征信等",
                "keywords", "逗号分隔匹配关键词",
                "answer_text", "标准应答文案",
                "tier", "层级：high_freq高频 cold冷门",
                "hit_count", "历史挖掘出现频次",
                "match_count", "运行时匹配次数",
                "transfer_count", "转顾问次数",
                "enabled", "是否启用：1是 0否",
                "create_time", "创建时间",
                "update_time", "更新时间"));
        cols(all, "prerecord_audio_clip", map(
                "id", "主键",
                "faq_id", "关联FAQ ID，null为全局片段",
                "clip_type", "片段类型：buffer/answer/closing/transfer等",
                "text_content", "对应文案",
                "wav_path", "8k电话wav文件路径",
                "variant_no", "同类型变体序号",
                "enabled", "是否启用：1是 0否",
                "create_time", "创建时间",
                "update_time", "更新时间"));
        return all;
    }

    private static void cols(Map<String, Map<String, String>> all, String table, Map<String, String> cols) {
        all.put(table, cols);
    }

    private static Map<String, String> map(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }
}
