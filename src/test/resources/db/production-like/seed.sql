-- A handful of seed users so the traffic simulator's first ticks (reports,
-- tickets, transactions all reference an existing user) have something to
-- point at immediately, before it has created any users itself.
INSERT INTO users (username, email, role, status, created_at) VALUES
    ('seed.customer1', 'seed.customer1@example.test', 'CUSTOMER', 'ACTIVE', '2026-07-01T08:00:00Z'),
    ('seed.customer2', 'seed.customer2@example.test', 'CUSTOMER', 'ACTIVE', '2026-07-01T08:00:00Z'),
    ('seed.agent1',    'seed.agent1@example.test',    'AGENT',    'ACTIVE', '2026-07-01T08:00:00Z');
