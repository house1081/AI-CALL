-- 音色来源：复刻音色 / 系统预置音色（MyBatis 实体 ttsVoiceMode、cosyvoiceSystemVoice）
-- 若列已存在会报错，可忽略

ALTER TABLE voice_runtime_config
  ADD COLUMN `tts_voice_mode` varchar(16) NOT NULL DEFAULT 'clone'
    COMMENT 'clone=复刻音色 | system=系统预置音色';

ALTER TABLE voice_runtime_config
  ADD COLUMN `cosyvoice_system_voice` varchar(64) DEFAULT 'longanyang'
    COMMENT '系统预置音色 voice 参数（如 longanyang）';

UPDATE voice_runtime_config
SET tts_voice_mode = 'clone',
    cosyvoice_system_voice = COALESCE(cosyvoice_system_voice, 'longanyang')
WHERE id = 1;
