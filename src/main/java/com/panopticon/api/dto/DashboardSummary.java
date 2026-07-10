package com.panopticon.api.dto;

import com.panopticon.model.DashboardDefinition;
import com.panopticon.model.RotationPolicy;

/**
 * Lightweight listing view of a dashboard (no panel definitions), used by
 * {@code GET /api/dashboards} so the picker/rotation UI doesn't have to pull
 * every panel/query for every dashboard just to render a list.
 */
public record DashboardSummary(String id, String title, String description, int panelCount, RotationPolicy rotation) {

    public static DashboardSummary from(DashboardDefinition dashboard) {
        return new DashboardSummary(
                dashboard.id(), dashboard.title(), dashboard.description(), dashboard.panels().size(), dashboard.rotation());
    }
}
