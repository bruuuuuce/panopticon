package com.panopticon.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Generic tabular result of executing a {@link DataDefinition} — the one
 * shape every {@link com.panopticon.data.DataProvider} returns, regardless
 * of whether the data came from SQL, a REST API, or anything else. This is
 * the only format the frontend ever consumes.
 *
 * <p>{@code status}/{@code errorMessage} let a provider report a failure as
 * data (useful for providers with "soft" failure modes) without every
 * provider having to agree on one Java exception hierarchy. In practice, a
 * {@code status == ERROR} result never reaches the frontend as-is:
 * {@link com.panopticon.data.DataEngine} turns it into a thrown
 * {@code DataExecutionException} so the existing HTTP error response and
 * negative-caching behavior keep working unchanged — the fields still exist
 * on the type because they're what a provider implementation constructs and
 * what {@code DataEngine} inspects.
 */
public record DataResult(
        List<ColumnDefinition> columns,
        List<Map<String, Object>> rows,
        Instant generatedAt,
        long executionTimeMs,
        int rowCount,
        DataResultStatus status,
        String errorMessage,
        String datasourceName
) {
    public static DataResult ok(List<ColumnDefinition> columns, List<Map<String, Object>> rows, Instant generatedAt, long executionTimeMs) {
        return new DataResult(columns, rows, generatedAt, executionTimeMs, rows.size(), DataResultStatus.OK, null, null);
    }

    public static DataResult error(String errorMessage) {
        return new DataResult(List.of(), List.of(), Instant.now(), 0, 0, DataResultStatus.ERROR, errorMessage, null);
    }

    /**
     * Providers build a {@link DataResult} with no notion of the connection's
     * human-facing name (only {@code DataEngine} resolves a
     * {@link com.panopticon.model.DataSourceDefinition}, see its javadoc on
     * staying provider-agnostic) — this lets {@code DataEngine} attach it
     * afterward, so a panel can show which connection its data actually came
     * from without every provider needing to know about display names.
     */
    public DataResult withDatasourceName(String datasourceName) {
        return new DataResult(columns, rows, generatedAt, executionTimeMs, rowCount, status, errorMessage, datasourceName);
    }
}
