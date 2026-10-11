-- Retained State snapshots (ADR 0026) for j2act-retained-jdbc, PostgreSQL.
-- One statement per line, so line-based runners such as Hibernate's default read it too.
CREATE TABLE IF NOT EXISTS j2act_retained_state (id VARCHAR(64) NOT NULL PRIMARY KEY, snapshot TEXT NOT NULL, expires_at BIGINT NOT NULL);
CREATE INDEX IF NOT EXISTS j2act_retained_state_expires ON j2act_retained_state (expires_at);
