ALTER TABLE conversation ADD COLUMN owner_id TEXT;
CREATE INDEX idx_conversation_owner ON conversation(owner_id);
