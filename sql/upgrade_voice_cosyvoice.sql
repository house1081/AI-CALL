-- 分段模式 CosyVoice 复刻音色（后台可改，无需重启服务）
ALTER TABLE voice_runtime_config
  ADD COLUMN `cosyvoice_clone_voice_id` varchar(128) DEFAULT NULL
    COMMENT 'CosyVoice 声音复刻 voice_id（分段模式 TTS/开场白）' AFTER omni_realtime_voice;
