package com.panopticon.model;

import java.util.List;

/**
 * Top-level dashboard-as-code definition, loaded from a JSON file under
 * {@code config/dashboards}.
 *
 * <p>{@code accentColor} is optional (null means "no accent, plain
 * background") and purely cosmetic: a hex color (e.g. {@code "#3987e5"})
 * the frontend washes into the page background as a bottom-to-top gradient,
 * so dashboards for different environments/systems are distinguishable at a
 * glance (particularly useful in monitor mode, cycling between several).
 * Panel surfaces themselves are never tinted by it — only the chrome behind
 * them — so it can't affect the validated palette's contrast guarantees.
 */
public record DashboardDefinition(
        String id,
        String title,
        String description,
        int gridColumns,
        List<PanelDefinition> panels,
        RotationPolicy rotation,
        String accentColor
) {
    public DashboardDefinition {
        panels = panels == null ? List.of() : List.copyOf(panels);
        if (gridColumns <= 0) {
            gridColumns = 12;
        }
        if (rotation == null) {
            rotation = RotationPolicy.defaultPolicy();
        }
        if (accentColor != null && accentColor.isBlank()) {
            accentColor = null;
        }
    }
}
