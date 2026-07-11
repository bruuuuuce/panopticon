package com.panopticon.model;

import java.util.List;

/**
 * A named, reusable data definition loaded from a JSON file under
 * {@code config/data}. Referenced by panels via {@code dataRef} so the
 * fetch logic (SQL, JQL, or whatever a future provider needs) is never
 * duplicated inside dashboard/panel definitions.
 *
 * <p>Fields below the common set ({@code id}/{@code provider}/
 * {@code datasource}/{@code timeoutMs}/{@code maxRows}/{@code cacheTtlSeconds})
 * are provider-specific and simply left {@code null} by providers that don't
 * use them: {@code sql} is JDBC-only; {@code operation}/{@code jql}/
 * {@code groupBy}/{@code fields} are Jira-only. A flat record with named,
 * typed fields was chosen over a generic {@code Map<String, Object>} bag —
 * with only two provider types today, an untyped map buys flexibility we
 * don't need yet at the cost of losing compile-time field names for the
 * providers we do have. Each {@link com.panopticon.data.DataProvider} only
 * reads the fields relevant to its own {@code providerType()}.
 */
public record DataDefinition(
        String id,
        String name,
        String provider,
        String datasource,
        Integer timeoutMs,
        Integer maxRows,
        Integer cacheTtlSeconds,
        // JDBC provider
        String sql,
        // Jira provider
        String operation,
        String jql,
        String groupBy,
        List<String> fields
) {
    private static final int DEFAULT_TIMEOUT_MS = 10_000;
    private static final int DEFAULT_MAX_ROWS = 1000;
    /** Short enough to keep panels feeling "live", long enough to collapse concurrent
     *  requests for the same data definition (multiple viewers, or a dataRef reused by several panels). */
    private static final int DEFAULT_CACHE_TTL_SECONDS = 10;

    public DataDefinition {
        if (timeoutMs == null || timeoutMs <= 0) {
            timeoutMs = DEFAULT_TIMEOUT_MS;
        }
        if (maxRows == null || maxRows <= 0) {
            maxRows = DEFAULT_MAX_ROWS;
        }
        if (cacheTtlSeconds == null) {
            cacheTtlSeconds = DEFAULT_CACHE_TTL_SECONDS;
        } else if (cacheTtlSeconds < 0) {
            // 0 is a valid, deliberate "never cache" setting; only negative values get sanitized.
            cacheTtlSeconds = 0;
        }
        fields = fields == null ? List.of() : List.copyOf(fields);
    }
}
