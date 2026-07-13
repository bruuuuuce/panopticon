package com.panopticon.data.plan;

/**
 * One structured access path extracted from a driver's raw {@code EXPLAIN}
 * output — e.g. "full table scan on TICKETS" or "index search on
 * IDX_USERS_STATUS". This is what a viewer actually wants out of a query
 * plan (which tables/indexes are touched and how), as opposed to the raw,
 * dialect-specific plan text alone.
 */
public record PlanStep(String subject, AccessType accessType, String detail) {

    public enum AccessType {
        /** Every row of a table was read — the main thing this view exists to flag. */
        FULL_SCAN,
        /** An index (or primary key) was used to narrow the rows read. */
        INDEX_SEARCH,
        /** A plan step that isn't a table access at all (sort, temp b-tree, etc.), or one this parser doesn't recognize. */
        OTHER
    }
}
