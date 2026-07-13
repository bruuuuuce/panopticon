package com.panopticon.data.stats;

import java.time.Instant;

/**
 * Per-{@code dataId} execution statistics for the "query stats" ops view —
 * a join of catalog metadata (name/provider/datasource, always present) with
 * whatever {@link QueryExecutionStatsTracker} has observed (all {@code null}
 * for a data definition that has never actually executed). {@code datasource}
 * is the technical config key (e.g. {@code "demo-h2"}); {@code
 * datasourceDisplayName} is the human-facing connection label — see
 * {@link com.panopticon.model.DataSourceDefinition}.
 */
public record QueryStatsSnapshot(
        String dataId,
        String name,
        String provider,
        String datasource,
        String datasourceDisplayName,
        Instant lastExecutedAt,
        Long lastDurationMs,
        Integer lastRowCount,
        String lastStatus,
        long totalExecutions,
        long totalErrors,
        Double avgDurationMs,
        Long minDurationMs,
        Instant minAt,
        Long maxDurationMs,
        Instant maxAt,
        Long p95DurationMs,
        Instant p95At,
        Long p99DurationMs,
        Instant p99At
) {
    public static QueryStatsSnapshot neverExecuted(
            String dataId, String name, String provider, String datasource, String datasourceDisplayName) {
        return new QueryStatsSnapshot(dataId, name, provider, datasource, datasourceDisplayName,
                null, null, null, null, 0, 0, null, null, null, null, null, null, null, null, null);
    }
}
