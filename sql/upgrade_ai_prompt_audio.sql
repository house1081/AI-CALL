-- 智能预录外呼：话术模板开场白/结束语上传录音
ALTER TABLE `ai_prompt`
  ADD COLUMN `opening_wav_path` varchar(500) DEFAULT NULL COMMENT '开场白录音路径' AFTER `end_remarks`;
ALTER TABLE `ai_prompt`
  ADD COLUMN `ending_wav_path` varchar(500) DEFAULT NULL COMMENT '结束语录音路径' AFTER `opening_wav_path`;
