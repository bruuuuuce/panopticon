package com.panopticon.it;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.panopticon.data.DataEngine;
import com.panopticon.data.recording.DataRecorder;
import com.panopticon.data.recording.RecordedExecution;
import com.panopticon.data.recording.RecordingImporter;
import com.panopticon.model.DataResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end recording round-trip inside the production-like suite: real
 * panel executions against the live, simulator-fed database get recorded to
 * the shared context's JSONL directory, and importing that file back into
 * the SAME prod-like SQLite database (table created from the shipped v1
 * script) must yield exactly the recorded lines, idempotently.
 */
class RecordingRoundTripIT extends AbstractProductionLikeIT {

    @Autowired
    private DataEngine dataEngine;

    @Autowired
    private DataRecorder recorder;

    @Autowired
    private RecordingImporter importer;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void recordsFreshExecutionsAndImportsThemBackIntoTheDatabase() throws Exception {
        assertThat(recorder.enabled()).isTrue();

        // tickets-kpi-open has cacheTtlSeconds: 0, so every execute() is a fresh
        // (and therefore recorded) execution.
        DataResult live = dataEngine.execute("tickets-kpi-open");
        assertThat(live.rows()).isNotEmpty();
        recorder.flush();

        Path recordingFile;
        try (var files = Files.list(recordingDir)) {
            recordingFile = files.findFirst().orElseThrow();
        }
        List<String> lines = Files.readAllLines(recordingFile);
        assertThat(lines).isNotEmpty();

        // Every line the whole suite has produced so far must parse back to the shared contract.
        RecordedExecution parsed = objectMapper.readValue(lines.get(lines.size() - 1), RecordedExecution.class);
        assertThat(parsed.datasource()).isEqualTo("prod-like-sqlite");

        // Import into the very same production-like database the dashboards read from.
        createRecordingTablesIfMissing();
        try {
            RecordingImporter.ImportReport report =
                    importer.importFile(recordingFile, "prod-like-sqlite", "panopticon_recordings");
            assertThat(report.malformed()).isZero();
            assertThat(report.inserted() + report.duplicates()).isEqualTo(lines.size());
            assertThat(rawDb.count("SELECT COUNT(*) FROM panopticon_recordings")).isGreaterThanOrEqualTo(lines.size());
            assertThat(rawDb.count(
                    "SELECT COUNT(*) FROM panopticon_recordings WHERE data_id = 'tickets-kpi-open' AND status = 'OK'"))
                    .isPositive();

            // Second run over the same file: nothing new may be inserted.
            long before = rawDb.count("SELECT COUNT(*) FROM panopticon_recordings");
            RecordingImporter.ImportReport again =
                    importer.importFile(recordingFile, "prod-like-sqlite", "panopticon_recordings");
            assertThat(again.inserted()).isZero();
            assertThat(rawDb.count("SELECT COUNT(*) FROM panopticon_recordings")).isEqualTo(before);
        } finally {
            // The shared database outlives this class; leave no recording tables behind.
            rawDb.execute("DROP TABLE IF EXISTS panopticon_recordings");
            rawDb.execute("DROP TABLE IF EXISTS panopticon_recordings_version");
        }
    }

    private void createRecordingTablesIfMissing() throws Exception {
        try (var in = getClass().getResourceAsStream("/db/recording/recording_table_v1.sql")) {
            String script = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            // Drop comment lines BEFORE splitting on ';' - comments may contain semicolons.
            String withoutComments = script.lines()
                    .filter(l -> !l.trim().startsWith("--"))
                    .reduce("", (a, b) -> a + "\n" + b);
            for (String statement : withoutComments.split(";")) {
                if (!statement.isBlank()) {
                    rawDb.execute(statement.trim());
                }
            }
        }
    }
}
