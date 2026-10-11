-- Retained State snapshots (ADR 0026) for j2act-retained-jdbc, MySQL. Ids are case-sensitive.
-- One statement per line, so line-based runners such as Hibernate's default read it too.
CREATE TABLE IF NOT EXISTS j2act_retained_state (id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY, snapshot LONGTEXT CHARACTER SET utf8mb4 NOT NULL, expires_at BIGINT NOT NULL, INDEX j2act_retained_state_expires (expires_at));
