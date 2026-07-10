package com.panopticon.model;

/**
 * How often a panel's data should be re-fetched by the frontend.
 */
public record RefreshPolicy(int intervalSeconds, boolean enabled) {

    public static RefreshPolicy disabled() {
        return new RefreshPolicy(0, false);
    }
}
