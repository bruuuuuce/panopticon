-- Seed 16 synthetic edge nodes with heartbeats relative to "now" (SQLite's
-- datetime('now', ...) modifiers), spread across 4 regions with a realistic
-- mostly-healthy status distribution.
INSERT INTO edge_nodes (id, node_name, region, status, last_heartbeat) VALUES
    (1,  'edge-us-east-1', 'us-east',  'ONLINE',   datetime('now', '-1 minutes')),
    (2,  'edge-us-east-2', 'us-east',  'ONLINE',   datetime('now', '-2 minutes')),
    (3,  'edge-us-east-3', 'us-east',  'DEGRADED', datetime('now', '-6 minutes')),
    (4,  'edge-us-east-4', 'us-east',  'ONLINE',   datetime('now', '-1 minutes')),
    (5,  'edge-us-west-1', 'us-west',  'ONLINE',   datetime('now', '-3 minutes')),
    (6,  'edge-us-west-2', 'us-west',  'ONLINE',   datetime('now', '-2 minutes')),
    (7,  'edge-us-west-3', 'us-west',  'OFFLINE',  datetime('now', '-47 minutes')),
    (8,  'edge-eu-west-1', 'eu-west',  'ONLINE',   datetime('now', '-1 minutes')),
    (9,  'edge-eu-west-2', 'eu-west',  'ONLINE',   datetime('now', '-4 minutes')),
    (10, 'edge-eu-west-3', 'eu-west',  'ONLINE',   datetime('now', '-2 minutes')),
    (11, 'edge-eu-west-4', 'eu-west',  'DEGRADED', datetime('now', '-9 minutes')),
    (12, 'edge-ap-south-1','ap-south', 'ONLINE',   datetime('now', '-3 minutes')),
    (13, 'edge-ap-south-2','ap-south', 'ONLINE',   datetime('now', '-2 minutes')),
    (14, 'edge-ap-south-3','ap-south', 'ONLINE',   datetime('now', '-1 minutes')),
    (15, 'edge-ap-south-4','ap-south', 'OFFLINE',  datetime('now', '-112 minutes')),
    (16, 'edge-ap-south-5','ap-south', 'ONLINE',   datetime('now', '-2 minutes'));
