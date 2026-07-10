package com.panopticon.model;

import java.time.Instant;

/**
 * Latest known execution status of a panel's query, tracked in-memory so the
 * frontend (and the /api/runtime/panels endpoint) can show refresh health.
 */
public record PanelRuntimeState(
        String dashboardId,
        String panelId,
        Instant lastRefreshAt,
        Instant lastSuccessAt,
        RuntimeStatus lastStatus,
        String lastError,
        long lastExecutionTimeMs
) {
    public enum RuntimeStatus {
        OK, ERROR, PENDING
    }
}
