package com.panopticon.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

/**
 * Supported panel widget types for the MVP.
 */
public enum PanelType {
    KPI,
    TABLE,
    BAR_CHART,
    LINE_CHART,
    DONUT_CHART;

    @JsonCreator
    public static PanelType fromJson(String value) {
        return PanelType.valueOf(value.trim().toUpperCase(Locale.ROOT));
    }

    @JsonValue
    public String toJson() {
        return name();
    }
}
