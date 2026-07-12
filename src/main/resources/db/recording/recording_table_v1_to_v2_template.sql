-- TEMPLATE for the next schema upgrade (v1 -> v2). Not runnable as-is: there
-- is no v2 yet. When a v2 becomes real, copy this file to
-- recording_table_v1_to_v2.sql, fill in the ALTERs, and bump
-- RecordingImporter.SCHEMA_VERSION so the importer refuses v1 tables until
-- they are upgraded.
--
-- Rules for every upgrade script:
--   1. Only additive/ALTER statements against panopticon_recordings.
--   2. Never rewrite existing rows' source_file/source_line (the natural key
--      import idempotency depends on).
--   3. Always append the new version row LAST, so a half-applied script
--      leaves the table still detectable as the old version.

-- ALTER TABLE panopticon_recordings ADD COLUMN <new_column> <type>;

INSERT INTO panopticon_recordings_version (version, applied_at) VALUES (2, '<date>');
