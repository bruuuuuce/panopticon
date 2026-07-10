package com.panopticon.model;

import java.util.List;

/**
 * Top-level dashboard-as-code definition, loaded from a JSON file under
 * {@code config/dashboards}.
 */
public record DashboardDefinition(
        String id,
        String title,
        String description,
        int gridColumns,
        List<PanelDefinition> panels,
        RotationPolicy rotation
) {
    public DashboardDefinition {
        panels = panels == null ? List.of() : List.copyOf(panels);
        if (gridColumns <= 0) {
            gridColumns = 12;
        }
        if (rotation == null) {
            rotation = RotationPolicy.defaultPolicy();
        }
    }
}
