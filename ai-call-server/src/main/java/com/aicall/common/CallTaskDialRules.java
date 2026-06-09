package com.aicall.common;

/**
 * 外呼任务默认规则说明（写入 task_rules，供商户查看）。
 */
public final class CallTaskDialRules {

    public static final String DEFAULT_TEXT =
            "单通通话结束后自动终止任务；振铃达到设定次数仍无人接听则记未接并终止；"
                    + "遵守风控外呼时段、单日呼出上限与线路并发；黑名单客户自动跳过。";

    private CallTaskDialRules() {
    }
}
