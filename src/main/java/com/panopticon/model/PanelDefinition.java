package com.panopticon.model;

import java.util.Map;

/**
 * A single panel on a dashboard. {@code options} carries panel-type-specific
 * rendering hints (e.g. which fields to use as axes/value/label) and is kept
 * as a loose map rather than a type hierarchy to avoid over-modeling the
 * MVP's small, evolving set of chart options.
 */
public record PanelDefinition(
        String id,
        String title,
        PanelType type,
        String queryRef,
        GridPosition grid,
        RefreshPolicy refresh,
        Map<String, Object> options
) {
    public PanelDefinition {
        options = options == null ? Map.of() : Map.copyOf(options);
    }
}
