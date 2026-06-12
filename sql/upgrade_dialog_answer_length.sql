-- 银行贷款话术包：部分标准答较长
ALTER TABLE `dialog_training_qa`
  MODIFY COLUMN `standard_answer` varchar(2000) NOT NULL COMMENT '人工标准回复';
