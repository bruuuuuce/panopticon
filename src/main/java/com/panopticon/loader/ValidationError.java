package com.panopticon.loader;

/**
 * A single problem found while validating dashboard/query configuration,
 * identifying the offending entity so it shows up clearly in the
 * {@code /api/config/validate} response.
 */
public record ValidationError(String source, String message) {
}
