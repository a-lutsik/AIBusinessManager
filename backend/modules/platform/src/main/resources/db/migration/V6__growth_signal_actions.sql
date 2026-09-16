-- Signal actionability + dismiss (design-function sync)
ALTER TABLE growth_signal ADD COLUMN IF NOT EXISTS severity VARCHAR(16) NOT NULL DEFAULT 'INFO';
ALTER TABLE growth_signal ADD COLUMN IF NOT EXISTS action_type VARCHAR(64);
ALTER TABLE growth_signal ADD COLUMN IF NOT EXISTS action_payload VARCHAR(2048);
ALTER TABLE growth_signal ADD COLUMN IF NOT EXISTS dismissed_at TIMESTAMP;
