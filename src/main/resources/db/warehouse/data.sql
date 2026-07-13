-- Seed 12 synthetic inventory items across 3 warehouses, with a couple of
-- deliberately low-stock rows so a "low stock" KPI has something to count.
INSERT INTO warehouse_items (id, sku, name, warehouse, quantity, updated_at) VALUES
    (1,  'SKU-1001', 'Steel bracket',      'north', 420, datetime('now', '-1 hours')),
    (2,  'SKU-1002', 'Steel bracket (L)',  'north', 180, datetime('now', '-2 hours')),
    (3,  'SKU-1003', 'Aluminum panel',     'north',   6, datetime('now', '-1 hours')),
    (4,  'SKU-1004', 'Aluminum panel (L)', 'north',  95, datetime('now', '-3 hours')),
    (5,  'SKU-2001', 'Rubber gasket',      'south', 640, datetime('now', '-1 hours')),
    (6,  'SKU-2002', 'Rubber seal',        'south',   9, datetime('now', '-2 hours')),
    (7,  'SKU-2003', 'Foam padding',       'south', 310, datetime('now', '-1 hours')),
    (8,  'SKU-3001', 'Copper wire (10m)',  'east',  275, datetime('now', '-4 hours')),
    (9,  'SKU-3002', 'Copper wire (50m)',  'east',   3, datetime('now', '-2 hours')),
    (10, 'SKU-3003', 'PVC conduit',        'east',  150, datetime('now', '-1 hours')),
    (11, 'SKU-3004', 'Junction box',       'east',   88, datetime('now', '-3 hours')),
    (12, 'SKU-3005', 'Cable tie (100pk)',  'east',  512, datetime('now', '-1 hours'));
