-- Retained State snapshots (ADR 0026) for j2act-retained-jdbc, Oracle. Run once: before 23ai,
-- Oracle has no CREATE TABLE IF NOT EXISTS.
-- One statement per line, so line-based runners such as Hibernate's default read it too.
CREATE TABLE j2act_retained_state (id VARCHAR2(64) NOT NULL PRIMARY KEY, snapshot CLOB NOT NULL, expires_at NUMBER(19) NOT NULL);
CREATE INDEX j2act_retained_state_expires ON j2act_retained_state (expires_at);
