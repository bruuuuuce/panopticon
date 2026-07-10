package com.panopticon.model;

/**
 * A named, reusable SQL query loaded from a JSON file under
 * {@code config/queries}. Referenced by panels via {@code queryRef} so SQL
 * is never duplicated inside dashboard/panel definitions.
 */
public record QueryDefinition(
        String id,
        String name,
        String datasource,
        String sql,
        Integer timeoutSeconds,
        Integer maxRows,
        Integer cacheTtlSeconds
) {
    private static final int DEFAULT_TIMEOUT_SECONDS = 10;
    private static final int DEFAULT_MAX_ROWS = 1000;
    /** Short enough to keep panels feeling "live", long enough to collapse concurrent
     *  requests for the same query (multiple viewers, or a queryRef reused by several panels). */
    private static final int DEFAULT_CACHE_TTL_SECONDS = 10;

    public QueryDefinition {
        if (timeoutSeconds == null || timeoutSeconds <= 0) {
            timeoutSeconds = DEFAULT_TIMEOUT_SECONDS;
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
    }
}
