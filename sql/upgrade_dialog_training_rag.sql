-- 对话训练 Q&A 知识库（RAG 热更新数据源）
CREATE TABLE IF NOT EXISTS `dialog_training_qa` (
  `id` int NOT NULL AUTO_INCREMENT,
  `question` varchar(500) NOT NULL COMMENT '用户问题/意图',
  `standard_answer` varchar(1000) NOT NULL COMMENT '人工标准回复',
  `data_type` tinyint NOT NULL DEFAULT 1 COMMENT '1人工修正 2优质样本 3负样本',
  `weight` decimal(4,2) NOT NULL DEFAULT 1.00 COMMENT '检索权重加成',
  `status` tinyint NOT NULL DEFAULT 1 COMMENT '1启用 0停用',
  `source_call_id` int DEFAULT NULL COMMENT '来源通话记录ID',
  `remark` varchar(200) DEFAULT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_status_type` (`status`, `data_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='对话训练标准问答（RAG）';
