package com.panopticon.model;

import java.time.Instant;

/**
 * Latest known execution outcome of a panel's query, tracked in-memory so
 * {@code GET /api/runtime/panels} can show refresh health across every
 * panel. {@code lastSuccess}/{@code lastFailure} are independent and both
 * "sticky": a success does not clear a prior failure's {@code lastError},
 * and a failure does not clear the {@code lastDurationMs}/{@code rowCount}
 * from the last successful run — so a currently-erroring panel still shows
 * "what it looked like when it last worked" alongside "what's wrong now".
 */
public record PanelRuntimeState(
        String dashboardId,
        String panelId,
        String queryRef,
        Instant lastSuccess,
        Instant lastFailure,
        long lastDurationMs,
        String lastError,
        int rowCount
) {
}
