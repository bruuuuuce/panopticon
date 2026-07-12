-- Recording import target, schema version 1.
--
-- Run this ONCE, by hand, against the database you want to import recordings
-- into (the importer deliberately never creates or alters tables on its own:
-- it refuses to run unless this table already exists at the expected version).
--
-- The default table name is panopticon_recordings; if you pass a different
-- --import-table, rename both tables here accordingly (the version table must
-- be named <table>_version).
--
-- Plain ANSI-ish SQL, tested against SQLite and H2. For other dialects adapt
-- the types (e.g. VARCHAR2/CLOB on Oracle) but keep columns and version rows
-- identical.

CREATE TABLE panopticon_recordings (
    source_file       VARCHAR(255)  NOT NULL,
    source_line       INTEGER       NOT NULL,
    recorded_at       VARCHAR(64)   NOT NULL,
    data_id           VARCHAR(255)  NOT NULL,
    datasource        VARCHAR(255),
    provider          VARCHAR(64),
    status            VARCHAR(16)   NOT NULL,
    execution_time_ms BIGINT,
    columns_json      VARCHAR(1000000),
    rows_json         VARCHAR(100000000),
    error_message     VARCHAR(4000),
    imported_at       VARCHAR(64)   NOT NULL,
    -- Natural key: re-importing the same file skips already-imported lines
    -- instead of duplicating them.
    PRIMARY KEY (source_file, source_line)
);

CREATE TABLE panopticon_recordings_version (
    version    INTEGER     NOT NULL,
    applied_at VARCHAR(64) NOT NULL
);

INSERT INTO panopticon_recordings_version (version, applied_at) VALUES (1, '2026-07-13T00:00:00Z');
