-- Demo schema for the bundled SQLite "local-sqlite" datasource. A small,
-- distinct table (edge node heartbeats) mainly to prove the jdbc provider
-- works against a second dialect, not just H2.
CREATE TABLE IF NOT EXISTS edge_nodes (
    id             INTEGER PRIMARY KEY,
    node_name      TEXT NOT NULL,
    region         TEXT NOT NULL,
    status         TEXT NOT NULL,
    last_heartbeat TEXT NOT NULL
);
