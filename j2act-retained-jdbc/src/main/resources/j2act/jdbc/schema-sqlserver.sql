-- Retained State snapshots (ADR 0026) for j2act-retained-jdbc, SQL Server 2016+. Ids are case-sensitive.
-- One statement per line, so line-based runners such as Hibernate's default read it too.
IF OBJECT_ID(N'j2act_retained_state', N'U') IS NULL CREATE TABLE j2act_retained_state (id VARCHAR(64) COLLATE Latin1_General_BIN2 NOT NULL PRIMARY KEY, snapshot NVARCHAR(MAX) NOT NULL, expires_at BIGINT NOT NULL, INDEX j2act_retained_state_expires (expires_at));
-- The cluster (ADR 0037): its servers, its secret and its stores' leases, for DatabaseDiscovery and DatabaseLeases.
IF OBJECT_ID(N'j2act_cluster_node', N'U') IS NULL CREATE TABLE j2act_cluster_node (url VARCHAR(255) COLLATE Latin1_General_BIN2 NOT NULL PRIMARY KEY, node_id VARCHAR(64) NOT NULL, seen_at BIGINT NOT NULL);
IF OBJECT_ID(N'j2act_cluster', N'U') IS NULL CREATE TABLE j2act_cluster (name VARCHAR(64) COLLATE Latin1_General_BIN2 NOT NULL PRIMARY KEY, val VARCHAR(255) COLLATE Latin1_General_BIN2 NOT NULL);
IF OBJECT_ID(N'j2act_shared_lease', N'U') IS NULL CREATE TABLE j2act_shared_lease (id VARCHAR(64) COLLATE Latin1_General_BIN2 NOT NULL PRIMARY KEY, holder_url VARCHAR(255) NOT NULL, holder_id VARCHAR(64) NOT NULL, epoch BIGINT NOT NULL, expires_at BIGINT NOT NULL);
