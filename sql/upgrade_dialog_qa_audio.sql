-- 训练知识库标准答录音（智能预录外呼播放用）
ALTER TABLE `dialog_training_qa`
  ADD COLUMN `answer_wav_path` varchar(500) DEFAULT NULL COMMENT '标准答录音路径' AFTER `standard_answer`;
