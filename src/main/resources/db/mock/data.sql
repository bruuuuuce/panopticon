-- Seed ~80 synthetic tickets spread over the last ~16 days, generated
-- relative to CURRENT_TIMESTAMP (via H2's SYSTEM_RANGE table function) so
-- the sample dashboards always look "live" regardless of when the app runs.
INSERT INTO tickets (id, subject, customer, priority, status, assignee, created_at, resolved_at)
SELECT
    x AS id,
    (CASE MOD(x, 5)
        WHEN 0 THEN 'Login failure'
        WHEN 1 THEN 'Billing discrepancy'
        WHEN 2 THEN 'API timeout'
        WHEN 3 THEN 'Data export issue'
        ELSE 'Feature request'
     END) || ' (#' || x || ')' AS subject,
    (CASE MOD(x, 6)
        WHEN 0 THEN 'Acme Corp'
        WHEN 1 THEN 'Globex'
        WHEN 2 THEN 'Initech'
        WHEN 3 THEN 'Umbrella Inc'
        WHEN 4 THEN 'Soylent Co'
        ELSE 'Stark Industries'
     END) AS customer,
    (CASE MOD(x, 4)
        WHEN 0 THEN 'CRITICAL'
        WHEN 1 THEN 'HIGH'
        WHEN 2 THEN 'MEDIUM'
        ELSE 'LOW'
     END) AS priority,
    (CASE MOD(x, 5)
        WHEN 0 THEN 'OPEN'
        WHEN 1 THEN 'IN_PROGRESS'
        WHEN 2 THEN 'PENDING'
        ELSE 'CLOSED'
     END) AS status,
    (CASE MOD(x, 4)
        WHEN 0 THEN 'A. Rossi'
        WHEN 1 THEN 'B. Chen'
        WHEN 2 THEN 'C. Diallo'
        ELSE 'D. Kim'
     END) AS assignee,
    DATEADD('HOUR', -5 * x, CURRENT_TIMESTAMP) AS created_at,
    -- Resolution time spans 1-90 hours (not a narrow band) so SLA/bucket-style
    -- queries in the query-performance sample dashboard have something to show
    -- across "fast", "same-day", "multi-day" and "breach" buckets alike.
    CASE WHEN MOD(x, 5) IN (3, 4)
        THEN DATEADD('HOUR', -5 * x + 1 + MOD(x * 13, 90), CURRENT_TIMESTAMP)
        ELSE NULL
    END AS resolved_at
FROM SYSTEM_RANGE(1, 80);

-- Seed ~120 synthetic payments spread over the last ~15 days, for the
-- payments-monitoring sample dashboard.
INSERT INTO payments (id, customer, amount, currency, method, status, created_at)
SELECT
    x AS id,
    (CASE MOD(x, 6)
        WHEN 0 THEN 'Acme Corp'
        WHEN 1 THEN 'Globex'
        WHEN 2 THEN 'Initech'
        WHEN 3 THEN 'Umbrella Inc'
        WHEN 4 THEN 'Soylent Co'
        ELSE 'Stark Industries'
     END) AS customer,
    ROUND(20 + MOD(x * 37, 480), 2) AS amount,
    'USD' AS currency,
    (CASE MOD(x, 4)
        WHEN 0 THEN 'CARD'
        WHEN 1 THEN 'BANK_TRANSFER'
        WHEN 2 THEN 'PAYPAL'
        ELSE 'WIRE'
     END) AS method,
    -- Mostly succeed, with a realistic minority of pending/refunded/failed.
    (CASE
        WHEN MOD(x, 20) = 0 THEN 'FAILED'
        WHEN MOD(x, 15) = 0 THEN 'REFUNDED'
        WHEN MOD(x, 7) = 0 THEN 'PENDING'
        ELSE 'SUCCEEDED'
     END) AS status,
    DATEADD('HOUR', -3 * x, CURRENT_TIMESTAMP) AS created_at
FROM SYSTEM_RANGE(1, 120);
