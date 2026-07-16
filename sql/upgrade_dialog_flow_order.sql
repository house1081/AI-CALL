-- 主线流程节点排序（后台可配置顺序）
ALTER TABLE `dialog_training_qa`
  ADD COLUMN `flow_order` int DEFAULT NULL COMMENT '主线节点排序（越小越靠前）' AFTER `remark`;
