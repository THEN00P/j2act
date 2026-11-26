-- Retained State snapshots (ADR 0026) for j2act-retained-jdbc, SQLite.
-- One statement per line, so line-based runners such as Hibernate's default read it too.
CREATE TABLE IF NOT EXISTS j2act_retained_state (id TEXT NOT NULL PRIMARY KEY, snapshot TEXT NOT NULL, expires_at INTEGER NOT NULL);
CREATE INDEX IF NOT EXISTS j2act_retained_state_expires ON j2act_retained_state (expires_at);
-- The cluster (ADR 0037): its servers, its secret and its stores' leases, for DatabaseDiscovery and DatabaseLeases.
CREATE TABLE IF NOT EXISTS j2act_cluster_node (url TEXT NOT NULL PRIMARY KEY, node_id TEXT NOT NULL, seen_at INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS j2act_cluster (name TEXT NOT NULL PRIMARY KEY, val TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS j2act_shared_lease (id TEXT NOT NULL PRIMARY KEY, holder_url TEXT NOT NULL, holder_id TEXT NOT NULL, epoch INTEGER NOT NULL, expires_at INTEGER NOT NULL);
