-- Demo schema for the bundled "secondary-sqlite" datasource. A second,
-- independent SQLite database (distinct file/connection from "local-sqlite")
-- specifically so multiple same-dialect datasources exist side by side -
-- proof that connection identification comes from config identity, not just
-- from dialect/driver.
CREATE TABLE IF NOT EXISTS warehouse_items (
    id         INTEGER PRIMARY KEY,
    sku        TEXT NOT NULL,
    name       TEXT NOT NULL,
    warehouse  TEXT NOT NULL,
    quantity   INTEGER NOT NULL,
    updated_at TEXT NOT NULL
);
