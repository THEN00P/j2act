-- Retained State snapshots (ADR 0026) for j2act-retained-jdbc, SQL Server 2016+. Ids are case-sensitive.
-- One statement per line, so line-based runners such as Hibernate's default read it too.
IF OBJECT_ID(N'j2act_retained_state', N'U') IS NULL CREATE TABLE j2act_retained_state (id VARCHAR(64) COLLATE Latin1_General_BIN2 NOT NULL PRIMARY KEY, snapshot NVARCHAR(MAX) NOT NULL, expires_at BIGINT NOT NULL, INDEX j2act_retained_state_expires (expires_at));
