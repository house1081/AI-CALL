-- 外呼语音运行时配置（句末档位、CosyVoice 音色、接通播报）
CREATE TABLE IF NOT EXISTS `voice_runtime_config` (
  `id` int NOT NULL COMMENT '固定为1',
  `dialog_pipeline_mode` varchar(32) NOT NULL DEFAULT 'turn-based' COMMENT 'legacy',
  `omni_realtime_voice` varchar(32) NOT NULL DEFAULT 'Ethan' COMMENT 'legacy',
  `omni_play_traditional_opening` tinyint NOT NULL DEFAULT 0 COMMENT 'legacy',
  `silence_profile` varchar(16) NOT NULL DEFAULT 'stable' COMMENT 'stable|fast',
  `cosyvoice_clone_voice_id` varchar(128) DEFAULT NULL COMMENT 'CosyVoice复刻voice_id',
  `tts_voice_mode` varchar(16) NOT NULL DEFAULT 'clone' COMMENT 'clone|system 音色来源',
  `cosyvoice_system_voice` varchar(64) DEFAULT 'longanyang' COMMENT '系统预置音色 voice 参数',
  `play_opening_on_answer` tinyint NOT NULL DEFAULT 1 COMMENT '接通后播报开场白',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT IGNORE INTO voice_runtime_config (id, dialog_pipeline_mode, silence_profile, play_opening_on_answer)
VALUES (1, 'turn-based', 'stable', 1);
