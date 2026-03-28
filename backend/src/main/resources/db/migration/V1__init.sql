-- V1__init.sql
-- Core schema for tree-structured LLM conversations

CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- Top-level conversation container
CREATE TABLE conversation (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    title       TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Each message is a node in the tree.
-- parent_id = NULL means root node.
-- Fork = inserting a new child under an existing node.
-- Branch = the path from root to any leaf, retrieved via recursive CTE.
CREATE TABLE node (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    conversation_id UUID NOT NULL REFERENCES conversation(id) ON DELETE CASCADE,
    parent_id       UUID REFERENCES node(id) ON DELETE CASCADE,
    role            TEXT NOT NULL CHECK (role IN ('user', 'assistant', 'system')),
    content         TEXT NOT NULL,
    provider        TEXT,
    model           TEXT,
    token_count     INTEGER,
    metadata        JSONB NOT NULL DEFAULT '{}',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_node_conversation ON node(conversation_id);
CREATE INDEX idx_node_parent ON node(parent_id);
CREATE INDEX idx_node_created ON node(created_at);

-- Trigger to auto-update conversation.updated_at when nodes change
CREATE OR REPLACE FUNCTION update_conversation_timestamp()
RETURNS TRIGGER AS $$
BEGIN
    UPDATE conversation SET updated_at = NOW() WHERE id = NEW.conversation_id;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_node_update_conversation
    AFTER INSERT ON node
    FOR EACH ROW
    EXECUTE FUNCTION update_conversation_timestamp();
