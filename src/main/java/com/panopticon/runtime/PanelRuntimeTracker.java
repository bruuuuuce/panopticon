package com.panopticon.runtime;

import com.panopticon.model.PanelRuntimeState;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory record of the last known execution outcome per panel, updated
 * every time a panel's data endpoint is hit. Intentionally not persisted:
 * this is refresh-health telemetry for the current process lifetime, not an
 * audit log.
 *
 * <p>Multiple viewers (browser tabs, monitor screens) can refresh the same
 * panel at effectively the same time, so updates go through
 * {@link ConcurrentHashMap#compute} rather than a get-then-put — the latter
 * would let one thread's update silently clobber another's under
 * concurrent access.
 */
@Component
public class PanelRuntimeTracker {

    private final ConcurrentHashMap<String, PanelRuntimeState> states = new ConcurrentHashMap<>();

    public void recordSuccess(String dashboardId, String panelId, String dataRef, long durationMs, int rowCount) {
        states.compute(key(dashboardId, panelId), (k, previous) -> new PanelRuntimeState(
                dashboardId, panelId, dataRef,
                Instant.now(),
                previous == null ? null : previous.lastFailure(),
                durationMs,
                previous == null ? null : previous.lastError(),
                rowCount));
    }

    public void recordFailure(String dashboardId, String panelId, String dataRef, String errorMessage) {
        states.compute(key(dashboardId, panelId), (k, previous) -> new PanelRuntimeState(
                dashboardId, panelId, dataRef,
                previous == null ? null : previous.lastSuccess(),
                Instant.now(),
                previous == null ? 0 : previous.lastDurationMs(),
                errorMessage,
                previous == null ? 0 : previous.rowCount()));
    }

    public List<PanelRuntimeState> all() {
        return states.values().stream()
                .sorted(Comparator.comparing(PanelRuntimeState::dashboardId).thenComparing(PanelRuntimeState::panelId))
                .toList();
    }

    private String key(String dashboardId, String panelId) {
        return dashboardId + "::" + panelId;
    }
}
