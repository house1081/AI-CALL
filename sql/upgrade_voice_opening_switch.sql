-- 接通播报开关（后台 voice_runtime_config.play_opening_on_answer）
ALTER TABLE voice_runtime_config
  ADD COLUMN IF NOT EXISTS play_opening_on_answer tinyint NOT NULL DEFAULT 1 COMMENT '接通后播报开场白';

UPDATE voice_runtime_config SET play_opening_on_answer = COALESCE(omni_play_traditional_opening, 1) WHERE id = 1;
