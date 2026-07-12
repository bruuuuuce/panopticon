package com.panopticon.data.recording;

import com.panopticon.model.ColumnDefinition;
import com.panopticon.model.DataResult;
import com.panopticon.model.DataSourceDefinition;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * One line of a recording file: a single fresh provider execution (success
 * or failure) of a data definition, with the full result payload. This is
 * the shared contract between {@link DataRecorder} (which serializes it as
 * one JSON Lines row) and {@link RecordingImporter} (which parses it back),
 * so the two can never drift apart.
 */
public record RecordedExecution(
        Instant recordedAt,
        String dataId,
        String datasource,
        String provider,
        String status,
        long executionTimeMs,
        List<ColumnDefinition> columns,
        List<Map<String, Object>> rows,
        String errorMessage
) {
    public static RecordedExecution success(String dataId, DataSourceDefinition datasource, DataResult result) {
        return new RecordedExecution(Instant.now(), dataId, datasource.name(), datasource.provider(),
                "OK", result.executionTimeMs(), result.columns(), result.rows(), null);
    }

    public static RecordedExecution failure(String dataId, DataSourceDefinition datasource, long executionTimeMs, String errorMessage) {
        return new RecordedExecution(Instant.now(), dataId, datasource.name(), datasource.provider(),
                "ERROR", executionTimeMs, List.of(), List.of(), errorMessage);
    }
}
