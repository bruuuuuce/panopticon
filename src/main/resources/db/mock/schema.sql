-- Demo schema for the bundled H2 "mock" datasource. Models a minimal
-- support-ticket table, enough to power the sample dashboards without
-- needing a real Oracle/Postgres instance to try Panopticon locally.
CREATE TABLE IF NOT EXISTS tickets (
    id           INT PRIMARY KEY,
    subject      VARCHAR(200)  NOT NULL,
    customer     VARCHAR(100)  NOT NULL,
    priority     VARCHAR(20)   NOT NULL,
    status       VARCHAR(20)   NOT NULL,
    assignee     VARCHAR(100)  NOT NULL,
    created_at   TIMESTAMP     NOT NULL,
    resolved_at  TIMESTAMP
);
