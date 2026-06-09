package com.aicall.common;

/** 对话训练数据分类 */
public final class DialogTrainingDataType {

    /** 人工修正问答对（一级优先级） */
    public static final int MANUAL_CORRECTION = 1;
    /** 优质通话样本 */
    public static final int QUALITY_SAMPLE = 2;
    /** 负样本（错误话术，用于约束规避） */
    public static final int NEGATIVE = 3;

    public static String label(int type) {
        return switch (type) {
            case MANUAL_CORRECTION -> "人工修正";
            case QUALITY_SAMPLE -> "优质样本";
            case NEGATIVE -> "负样本";
            default -> "未知";
        };
    }

    public static boolean isPositive(int type) {
        return type == MANUAL_CORRECTION || type == QUALITY_SAMPLE;
    }

    private DialogTrainingDataType() {}
}
