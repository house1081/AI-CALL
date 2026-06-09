package com.aicall.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 对话训练 RAG 热更新配置（方案一：本地向量库，可选 Milvus 扩展）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "dialog-rag")
public class DialogRagProperties {

    /** 启用训练数据 RAG 检索 */
    private boolean enabled = true;
    /** 向量存储：local=内存热加载；milvus=远程 Milvus（需单独部署） */
    private String storeType = "local";
    private String milvusHost = "127.0.0.1";
    private int milvusPort = 19530;
    private String milvusCollection = "dialog_training_qa";
    /** DashScope 嵌入模型 */
    private String embeddingModel = "text-embedding-v3";
    /** 检索 top-k */
    private int topK = 3;
    /** 加权后分数 ≥ 此值且为正向样本时直出标准答案（跳过 LLM，保延迟） */
    private double directAnswerScore = 0.82;
    /** 加权后分数 ≥ 此值注入 Prompt；低于则走无匹配兜底 */
    private double minRetrieveScore = 0.55;
    /** 无匹配时跳过 LLM，直接播兜底话术（禁止模型自由发挥） */
    private boolean strictNoMatchFallback = true;
    /** 人工修正默认权重 */
    private double manualCorrectionWeight = 3.0;
    /** 优质样本默认权重 */
    private double qualitySampleWeight = 2.0;
    /** 负样本默认权重（用于规避错误输出） */
    private double negativeSampleWeight = 1.5;
    /** 启用银行贷款主线循序话术（01→29，覆盖开场白） */
    private boolean loanMainFlowEnabled = true;
    /** 库为空时启动自动导入 classpath 话术包 */
    private boolean loanPackAutoImport = true;
}
