-- Retained State snapshots (ADR 0026) for j2act-retained-jdbc, MariaDB. Ids are case-sensitive.
-- One statement per line, so line-based runners such as Hibernate's default read it too.
CREATE TABLE IF NOT EXISTS j2act_retained_state (id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY, snapshot LONGTEXT CHARACTER SET utf8mb4 NOT NULL, expires_at BIGINT NOT NULL, INDEX j2act_retained_state_expires (expires_at));
-- The cluster (ADR 0037): its servers, its secret and its stores' leases, for DatabaseDiscovery and DatabaseLeases.
CREATE TABLE IF NOT EXISTS j2act_cluster_node (url VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY, node_id VARCHAR(64) CHARACTER SET ascii NOT NULL, seen_at BIGINT NOT NULL);
CREATE TABLE IF NOT EXISTS j2act_cluster (name VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY, val VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL);
CREATE TABLE IF NOT EXISTS j2act_shared_lease (id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY, holder_url VARCHAR(255) CHARACTER SET ascii NOT NULL, holder_id VARCHAR(64) CHARACTER SET ascii NOT NULL, epoch BIGINT NOT NULL, expires_at BIGINT NOT NULL);
