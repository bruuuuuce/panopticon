package com.panopticon.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Generic tabular result of executing a {@link QueryDefinition}, shaped for
 * direct JSON serialization to the frontend.
 */
public record QueryResult(
        List<ColumnMeta> columns,
        List<Map<String, Object>> rows,
        Instant generatedAt,
        long executionTimeMs,
        int rowCount
) {
}
