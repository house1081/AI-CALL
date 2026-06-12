-- 强制挂断：通话记录挂断类型
USE `ai-call`;

ALTER TABLE `call_record`
  ADD COLUMN `hangup_type` varchar(50) DEFAULT NULL COMMENT '正常结束/强制挂断-*' AFTER `call_status`;
