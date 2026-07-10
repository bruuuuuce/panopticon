package com.panopticon.model;

/**
 * How long a dashboard is displayed in monitor (rotation) mode before
 * advancing to the next one.
 */
public record RotationPolicy(int durationSeconds, boolean enabled) {

    public static RotationPolicy defaultPolicy() {
        return new RotationPolicy(30, true);
    }
}
