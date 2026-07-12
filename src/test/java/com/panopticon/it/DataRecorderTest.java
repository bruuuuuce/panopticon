package com.panopticon.it;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.panopticon.config.RecordingSettings;
import com.panopticon.data.recording.DataRecorder;
import com.panopticon.data.recording.RecordedExecution;
import com.panopticon.model.ColumnDefinition;
import com.panopticon.model.DataResult;
import com.panopticon.model.DataSourceDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DataRecorder writes one parseable RecordedExecution JSON line per recorded
 * execution to a daily-rolling file. Round-trip through the same ObjectMapper
 * the importer uses is the real contract: what the recorder writes, the
 * importer must be able to read back unchanged.
 */
class DataRecorderTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void writesOneParseableJsonLinePerExecution(@TempDir Path dir) throws Exception {
        try (DataRecorder recorder = new DataRecorder(RecordingSettings.enabledAt(dir), objectMapper)) {
            DataResult result = DataResult.ok(
                    List.of(new ColumnDefinition("open_tickets", "INTEGER")),
                    List.of(Map.of("open_tickets", 42)),
                    Instant.now(), 7);
            recorder.record(RecordedExecution.success("kpi-open-tickets", datasource(), result));
            recorder.record(RecordedExecution.failure("fault-missing-table", datasource(), 3,
                    "no such table: legacy_billing_ledger"));
            recorder.flush();

            Path file = dir.resolve("panopticon-%s.jsonl".formatted(LocalDate.now()));
            assertThat(file).exists();
            List<String> lines = Files.readAllLines(file);
            assertThat(lines).hasSize(2);

            RecordedExecution first = objectMapper.readValue(lines.get(0), RecordedExecution.class);
            assertThat(first.dataId()).isEqualTo("kpi-open-tickets");
            assertThat(first.status()).isEqualTo("OK");
            assertThat(first.datasource()).isEqualTo("ds1");
            assertThat(first.provider()).isEqualTo("jdbc");
            assertThat(first.executionTimeMs()).isEqualTo(7);
            assertThat(first.columns()).containsExactly(new ColumnDefinition("open_tickets", "INTEGER"));
            assertThat(first.rows()).hasSize(1);
            assertThat(first.rows().get(0)).containsEntry("open_tickets", 42);
            assertThat(first.errorMessage()).isNull();

            RecordedExecution second = objectMapper.readValue(lines.get(1), RecordedExecution.class);
            assertThat(second.status()).isEqualTo("ERROR");
            assertThat(second.errorMessage()).contains("legacy_billing_ledger");
            assertThat(second.rows()).isEmpty();
        }
    }

    @Test
    void appendsAcrossRecorderRestarts(@TempDir Path dir) throws Exception {
        // A restart during the same day must append to the existing file, not truncate it.
        try (DataRecorder first = new DataRecorder(RecordingSettings.enabledAt(dir), objectMapper)) {
            first.record(RecordedExecution.failure("d1", datasource(), 1, "boom"));
            first.flush();
        }
        try (DataRecorder second = new DataRecorder(RecordingSettings.enabledAt(dir), objectMapper)) {
            second.record(RecordedExecution.failure("d2", datasource(), 1, "boom"));
            second.flush();
        }
        Path file = dir.resolve("panopticon-%s.jsonl".formatted(LocalDate.now()));
        assertThat(Files.readAllLines(file)).hasSize(2);
    }

    @Test
    void disabledRecorderWritesNothing(@TempDir Path dir) throws Exception {
        try (DataRecorder recorder = new DataRecorder(RecordingSettings.disabled(), objectMapper)) {
            assertThat(recorder.enabled()).isFalse();
            recorder.record(RecordedExecution.failure("d1", datasource(), 1, "boom"));
            recorder.flush();
        }
        try (var files = Files.list(dir)) {
            assertThat(files).isEmpty();
        }
    }

    private DataSourceDefinition datasource() {
        return new DataSourceDefinition("ds1", "jdbc", "jdbc:h2:mem:x", "sa", "", "org.h2.Driver",
                "h2", true, 1, null, null, null, null, null);
    }
}
