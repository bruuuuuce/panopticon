package com.panopticon.runtime;

import com.panopticon.model.PanelRuntimeState;
import com.panopticon.model.PanelRuntimeState.RuntimeStatus;
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
 */
@Component
public class PanelRuntimeTracker {

    private final ConcurrentHashMap<String, PanelRuntimeState> states = new ConcurrentHashMap<>();

    public void recordSuccess(String dashboardId, String panelId, long executionTimeMs) {
        Instant now = Instant.now();
        states.put(key(dashboardId, panelId),
                new PanelRuntimeState(dashboardId, panelId, now, now, RuntimeStatus.OK, null, executionTimeMs));
    }

    public void recordError(String dashboardId, String panelId, String errorMessage) {
        PanelRuntimeState previous = states.get(key(dashboardId, panelId));
        Instant lastSuccessAt = previous == null ? null : previous.lastSuccessAt();
        states.put(key(dashboardId, panelId),
                new PanelRuntimeState(dashboardId, panelId, Instant.now(), lastSuccessAt, RuntimeStatus.ERROR, errorMessage, 0));
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
