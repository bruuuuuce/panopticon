-- Production-like schema for the com.panopticon.it integration suite: the kind
-- of tables a microservices system doing user management, abuse/incident
-- reports ("segnalazioni"), support ticketing, payment transactions and an
-- async store-and-process event pipeline would actually have. Populated in
-- real time by ProductionTrafficSimulator, not by this file (seed.sql only
-- seeds a handful of users so the first ticks have something to reference).

CREATE TABLE IF NOT EXISTS users (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    username   TEXT NOT NULL UNIQUE,
    email      TEXT NOT NULL,
    role       TEXT NOT NULL,   -- CUSTOMER, AGENT, ADMIN
    status     TEXT NOT NULL,   -- ACTIVE, SUSPENDED, DELETED
    created_at TEXT NOT NULL
);

-- "segnalazioni": abuse/incident reports raised by a user against another
-- entity (a user, a ticket, a transaction, a piece of content).
CREATE TABLE IF NOT EXISTS reports (
    id                INTEGER PRIMARY KEY AUTOINCREMENT,
    reporter_user_id  INTEGER NOT NULL,
    target_type       TEXT NOT NULL,   -- USER, TICKET, TRANSACTION, CONTENT
    target_id         INTEGER,
    reason            TEXT NOT NULL,
    severity          TEXT NOT NULL,   -- LOW, MEDIUM, HIGH, CRITICAL
    status            TEXT NOT NULL,   -- NEW, UNDER_REVIEW, ESCALATED, DISMISSED, RESOLVED
    created_at        TEXT NOT NULL,
    resolved_at       TEXT,
    FOREIGN KEY (reporter_user_id) REFERENCES users(id)
);

CREATE TABLE IF NOT EXISTS tickets (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id      INTEGER NOT NULL,
    subject      TEXT NOT NULL,
    status       TEXT NOT NULL,   -- OPEN, IN_PROGRESS, WAITING_CUSTOMER, RESOLVED, CLOSED
    priority     TEXT NOT NULL,   -- LOW, MEDIUM, HIGH, CRITICAL
    created_at   TEXT NOT NULL,
    updated_at   TEXT NOT NULL,
    resolved_at  TEXT,
    FOREIGN KEY (user_id) REFERENCES users(id)
);

CREATE TABLE IF NOT EXISTS transactions (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id      INTEGER NOT NULL,
    amount_cents INTEGER NOT NULL,
    currency     TEXT NOT NULL,
    status       TEXT NOT NULL,   -- PENDING, AUTHORIZED, CAPTURED, SETTLED, FAILED, REFUNDED
    created_at   TEXT NOT NULL,
    updated_at   TEXT NOT NULL,
    FOREIGN KEY (user_id) REFERENCES users(id)
);

-- Classic transactional outbox / store-and-process pattern: a worker claims a
-- PENDING (or due RETRY_SCHEDULED) row, moves it to PROCESSING, then to a
-- terminal PROCESSED/FAILED/DEAD_LETTER state or back to RETRY_SCHEDULED.
CREATE TABLE IF NOT EXISTS event_outbox (
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    aggregate_type TEXT NOT NULL,   -- USER, REPORT, TICKET, TRANSACTION
    aggregate_id   INTEGER NOT NULL,
    event_type     TEXT NOT NULL,
    status         TEXT NOT NULL,   -- PENDING, PROCESSING, PROCESSED, RETRY_SCHEDULED, FAILED, DEAD_LETTER
    attempts       INTEGER NOT NULL DEFAULT 0,
    max_attempts   INTEGER NOT NULL DEFAULT 5,
    locked_by      TEXT,
    locked_at      TEXT,
    created_at     TEXT NOT NULL,
    processed_at   TEXT,
    last_error     TEXT
);

-- Deliberately never written to by the simulator - a stable, non-racy fixture
-- for the "dashboard reads a genuinely empty table" scenario (as opposed to a
-- populated table filtered down to zero rows, which is also covered but is
-- racy under concurrent writers).
CREATE TABLE IF NOT EXISTS audit_log (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    actor      TEXT NOT NULL,
    action     TEXT NOT NULL,
    created_at TEXT NOT NULL
);

-- Used only by the schema-drift scenario: a test renames total_amount mid-run
-- (simulating another service's out-of-band migration) to prove a data
-- definition bound to the old column name fails cleanly instead of crashing
-- the app or corrupting other panels.
CREATE TABLE IF NOT EXISTS legacy_billing_summary (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    period       TEXT NOT NULL,
    total_amount INTEGER NOT NULL
);
INSERT INTO legacy_billing_summary (period, total_amount) VALUES ('2026-Q1', 100000);
