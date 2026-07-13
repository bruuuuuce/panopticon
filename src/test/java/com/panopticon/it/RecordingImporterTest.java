package com.panopticon.it;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.panopticon.config.RecordingSettings;
import com.panopticon.data.recording.DataRecorder;
import com.panopticon.data.recording.RecordedExecution;
import com.panopticon.data.recording.RecordingImporter;
import com.panopticon.it.support.RawDb;
import com.panopticon.model.ColumnDefinition;
import com.panopticon.model.DataResult;
import com.panopticon.model.DataSourceDefinition;
import com.panopticon.registry.DataSourceRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Full recording round-trip at the unit level, against a real temp-file
 * SQLite database: the shipped recording_table_v1.sql script is executed
 * as-is (proving the DDL actually runs), a DataRecorder-produced file is
 * imported, contents are verified with an independent JDBC probe, and a
 * re-import must be a no-op (idempotency via the (source_file, source_line)
 * natural key). No Spring context involved.
 */
class RecordingImporterTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void importsRecordedFileAndReimportIsIdempotent(@TempDir Path dir) throws Exception {
        Path dbFile = dir.resolve("import-target.db");
        Path recordingFile = record(dir,
                RecordedExecution.success("kpi-open-tickets", datasource(dbFile), DataResult.ok(
                        List.of(new ColumnDefinition("open_tickets", "INTEGER")),
                        List.of(Map.of("open_tickets", 42)),
                        Instant.now(), 7)),
                RecordedExecution.failure("fault-missing-table", datasource(dbFile), 3, "no such table"));

        RawDb db = new RawDb("jdbc:sqlite:" + dbFile);
        runV1Script(db);
        RecordingImporter importer = importer(dbFile);

        RecordingImporter.ImportReport report = importer.importFile(recordingFile, "import-target", "panopticon_recordings");
        assertThat(report.inserted()).isEqualTo(2);
        assertThat(report.duplicates()).isZero();
        assertThat(report.malformed()).isZero();

        assertThat(db.count("SELECT COUNT(*) FROM panopticon_recordings")).isEqualTo(2);
        assertThat(db.scalar("SELECT status FROM panopticon_recordings WHERE data_id = 'kpi-open-tickets'"))
                .contains("OK");
        assertThat((String) db.scalar("SELECT rows_json FROM panopticon_recordings WHERE data_id = 'kpi-open-tickets'").orElseThrow())
                .contains("\"open_tickets\":42");
        assertThat((String) db.scalar("SELECT error_message FROM panopticon_recordings WHERE data_id = 'fault-missing-table'").orElseThrow())
                .contains("no such table");

        RecordingImporter.ImportReport again = importer.importFile(recordingFile, "import-target", "panopticon_recordings");
        assertThat(again.inserted()).isZero();
        assertThat(again.duplicates()).isEqualTo(2);
        assertThat(db.count("SELECT COUNT(*) FROM panopticon_recordings")).isEqualTo(2);
    }

    @Test
    void refusesToImportWhenTargetTableIsMissing(@TempDir Path dir) throws Exception {
        Path dbFile = dir.resolve("empty.db");
        Path recordingFile = record(dir,
                RecordedExecution.failure("d1", datasource(dbFile), 1, "boom"));

        new RawDb("jdbc:sqlite:" + dbFile).scalar("SELECT 1"); // materialize the db file
        assertThatThrownBy(() -> importer(dbFile).importFile(recordingFile, "import-target", "panopticon_recordings"))
                .isInstanceOf(RecordingImporter.ImportException.class)
                .hasMessageContaining("recording_table_v1.sql");
    }

    @Test
    void refusesReadOnlyDatasourceAndBadTableNames(@TempDir Path dir) throws Exception {
        Path dbFile = dir.resolve("t.db");
        Path recordingFile = record(dir,
                RecordedExecution.failure("d1", datasource(dbFile), 1, "boom"));

        DataSourceDefinition readOnly = new DataSourceDefinition("ro", null, "jdbc", "jdbc:sqlite:" + dbFile,
                null, null, "org.sqlite.JDBC", "sqlite", true, 1, null, null, null, null, null);
        RecordingImporter importer = new RecordingImporter(
                new DataSourceRegistry(Map.of("ro", readOnly)), objectMapper);

        assertThatThrownBy(() -> importer.importFile(recordingFile, "ro", "panopticon_recordings"))
                .isInstanceOf(RecordingImporter.ImportException.class)
                .hasMessageContaining("read-only");
        assertThatThrownBy(() -> importer.importFile(recordingFile, "nope", "panopticon_recordings"))
                .isInstanceOf(RecordingImporter.ImportException.class)
                .hasMessageContaining("Unknown datasource");
        assertThatThrownBy(() -> importer.importFile(recordingFile, "ro", "bad name; drop table x"))
                .isInstanceOf(RecordingImporter.ImportException.class)
                .hasMessageContaining("Invalid table name");
    }

    @Test
    void countsMalformedLinesWithoutAbortingTheImport(@TempDir Path dir) throws Exception {
        Path dbFile = dir.resolve("m.db");
        Path recordingFile = record(dir,
                RecordedExecution.failure("d1", datasource(dbFile), 1, "boom"));
        Files.writeString(recordingFile,
                Files.readString(recordingFile) + "this is not json\n", StandardCharsets.UTF_8);

        RawDb db = new RawDb("jdbc:sqlite:" + dbFile);
        runV1Script(db);
        RecordingImporter.ImportReport report =
                importer(dbFile).importFile(recordingFile, "import-target", "panopticon_recordings");
        assertThat(report.inserted()).isEqualTo(1);
        assertThat(report.malformed()).isEqualTo(1);
        assertThat(db.count("SELECT COUNT(*) FROM panopticon_recordings")).isEqualTo(1);
    }

    /** Writes the given executions to a recording file via a real DataRecorder and returns its path. */
    private Path record(Path dir, RecordedExecution... executions) throws IOException {
        Path recordingDir = dir.resolve("recordings");
        try (DataRecorder recorder = new DataRecorder(RecordingSettings.enabledAt(recordingDir), objectMapper)) {
            for (RecordedExecution execution : executions) {
                recorder.record(execution);
            }
            recorder.flush();
        }
        try (var files = Files.list(recordingDir)) {
            return files.findFirst().orElseThrow();
        }
    }

    private RecordingImporter importer(Path dbFile) {
        return new RecordingImporter(
                new DataSourceRegistry(Map.of("import-target", datasource(dbFile))), objectMapper);
    }

    private DataSourceDefinition datasource(Path dbFile) {
        return new DataSourceDefinition("import-target", null, "jdbc", "jdbc:sqlite:" + dbFile,
                null, null, "org.sqlite.JDBC", "sqlite", false, 1, null, null, null, null, null);
    }

    /** Runs the shipped v1 DDL exactly as a user would, statement by statement. */
    private void runV1Script(RawDb db) throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/db/recording/recording_table_v1.sql")) {
            String script = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            // Drop comment lines BEFORE splitting on ';' - comments may contain semicolons.
            String withoutComments = script.lines()
                    .filter(l -> !l.trim().startsWith("--"))
                    .reduce("", (a, b) -> a + "\n" + b);
            for (String statement : withoutComments.split(";")) {
                if (!statement.isBlank()) {
                    db.execute(statement.trim());
                }
            }
        }
    }
}
