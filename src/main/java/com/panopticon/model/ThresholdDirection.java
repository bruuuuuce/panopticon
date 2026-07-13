package com.panopticon.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

/**
 * Which way a value has to move past a {@link ThresholdRule}'s bound to count
 * as a breach: {@code ABOVE} for metrics where high is bad (queue length,
 * open tickets), {@code BELOW} for metrics where low is bad (stock quantity,
 * uptime percentage).
 */
public enum ThresholdDirection {
    ABOVE,
    BELOW;

    @JsonCreator
    public static ThresholdDirection fromJson(String value) {
        return ThresholdDirection.valueOf(value.trim().toUpperCase(Locale.ROOT));
    }

    @JsonValue
    public String toJson() {
        return name().toLowerCase(Locale.ROOT);
    }
}
