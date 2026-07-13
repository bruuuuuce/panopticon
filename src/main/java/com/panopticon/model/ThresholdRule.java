package com.panopticon.model;

/**
 * A fixed alerting bound on one field of a panel's data, evaluated by the
 * frontend against every row of the panel's latest {@link DataResult} (see
 * {@code thresholds.js}) — the backend only carries and validates the
 * config, it never itself decides whether a value is "bad".
 *
 * <p>{@code field} is independent of a panel's rendering {@code options}
 * (e.g. {@code valueField}/{@code columns}): a table can threshold a column
 * it doesn't even display. At least one of {@code warning}/{@code critical}
 * must be set (enforced by {@code ConfigValidator}); either may be omitted
 * to alert on only one severity.
 */
public record ThresholdRule(
        String field,
        String label,
        Double warning,
        Double critical,
        ThresholdDirection direction
) {
    public ThresholdRule {
        if (direction == null) {
            direction = ThresholdDirection.ABOVE;
        }
    }
}
