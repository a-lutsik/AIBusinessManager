-- Track which AI provider produced each prompt, separately from the model name.
ALTER TABLE ai_prompt_log
    ADD COLUMN IF NOT EXISTS provider VARCHAR(64) NOT NULL DEFAULT 'deepseek';
