package com.aicall.common;

/** PRD 强制挂断：挂断类型（写死，不可配置） */
public final class HangupType {
    public static final String NORMAL = "正常结束";
    /** 辱骂、脏话、投诉举报等 */
    public static final String FORCE_ABUSE = "强制挂断-辱骂投诉";
    /** 客户明确表示不希望再被打扰 */
    public static final String FORCE_REFUSE = "强制挂断-拒绝打扰";
    /** 通话超过 5 分钟 */
    public static final String FORCE_DURATION = "强制挂断-通话超时";
    /** 商户暂停外呼任务 */
    public static final String TASK_PAUSED = "任务暂停挂断";
    /** 商户终止外呼任务 */
    public static final String TASK_TERMINATED = "任务终止挂断";
    /** 兼容历史数据 */
    public static final String FORCE_CHAT = "强制挂断-闲聊超时";
    public static final String FORCE_UNCOOP = "强制挂断-客户不配合";

    public static boolean isForced(String type) {
        return type != null && type.startsWith("强制挂断");
    }

    /** 商户端仅展示「强制挂断」 */
    public static String tenantLabel(String type) {
        if (TRANSFER_HUMAN.equals(type)) {
            return "转人工";
        }
        if (NO_RESPONSE.equals(type)) {
            return "无人应答";
        }
        return isForced(type) ? "强制挂断" : (type != null && !type.isBlank() ? type : "-");
    }

    /** 敏感词/违规词触发转人工 */
    public static final String TRANSFER_HUMAN = "转人工";
    /** 静默追问达上限，无人应答挂机 */
    public static final String NO_RESPONSE = "无人应答";

    private HangupType() {}
}
