-- Retained State snapshots (ADR 0026) for j2act-retained-jdbc, Oracle. Run once: before 23ai,
-- Oracle has no CREATE TABLE IF NOT EXISTS.
-- One statement per line, so line-based runners such as Hibernate's default read it too.
CREATE TABLE j2act_retained_state (id VARCHAR2(64) NOT NULL PRIMARY KEY, snapshot CLOB NOT NULL, expires_at NUMBER(19) NOT NULL);
CREATE INDEX j2act_retained_state_expires ON j2act_retained_state (expires_at);
-- The cluster (ADR 0037): its servers, its secret and its stores' leases, for DatabaseDiscovery and DatabaseLeases.
CREATE TABLE j2act_cluster_node (url VARCHAR2(255) NOT NULL PRIMARY KEY, node_id VARCHAR2(64) NOT NULL, seen_at NUMBER(19) NOT NULL);
CREATE TABLE j2act_cluster (name VARCHAR2(64) NOT NULL PRIMARY KEY, val VARCHAR2(255) NOT NULL);
CREATE TABLE j2act_shared_lease (id VARCHAR2(64) NOT NULL PRIMARY KEY, holder_url VARCHAR2(255) NOT NULL, holder_id VARCHAR2(64) NOT NULL, epoch NUMBER(19) NOT NULL, expires_at NUMBER(19) NOT NULL);
