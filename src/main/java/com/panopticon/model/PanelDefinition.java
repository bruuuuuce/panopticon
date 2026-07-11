package com.panopticon.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

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
        String dataRef,
        GridPosition grid,
        RefreshPolicy refresh,
        Map<String, Object> options
) {
    public PanelDefinition {
        options = options == null ? Map.of() : Map.copyOf(options);
    }

    /**
     * {@code dataRef} is the current field name; {@code queryRef} is accepted
     * as a temporary fallback for dashboard JSON written before the Query ->
     * Data rename, so existing files don't need to change to keep working.
     */
    @JsonCreator
    public static PanelDefinition fromJson(
            @JsonProperty("id") String id,
            @JsonProperty("title") String title,
            @JsonProperty("type") PanelType type,
            @JsonProperty("dataRef") String dataRef,
            @JsonProperty("queryRef") String queryRef,
            @JsonProperty("grid") GridPosition grid,
            @JsonProperty("refresh") RefreshPolicy refresh,
            @JsonProperty("options") Map<String, Object> options) {
        String resolvedRef = (dataRef != null && !dataRef.isBlank()) ? dataRef : queryRef;
        return new PanelDefinition(id, title, type, resolvedRef, grid, refresh, options);
    }
}
