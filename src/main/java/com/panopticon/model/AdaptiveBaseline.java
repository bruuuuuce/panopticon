package com.panopticon.model;

/**
 * The raw statistics an adaptive threshold decision is based on: the recent
 * mean/sample-standard-deviation of one panel's {@code stat} value field,
 * computed by {@code AdaptiveThresholdTracker} from its execution history.
 * Attached to a {@link DataResult} by {@code DashboardController} the same
 * way {@code datasourceName} is (see {@link DataResult#withDatasourceName}).
 *
 * <p>Like fixed {@link ThresholdRule}s, the backend only supplies numbers —
 * it never itself decides whether the current value is an anomaly; that
 * z-score/level decision is made client-side (see {@code thresholds.js}).
 * {@code null} on a {@link DataResult} means either the panel isn't a
 * {@code stat}, or there aren't yet enough recorded samples to compute a
 * meaningful baseline.
 */
public record AdaptiveBaseline(int sampleCount, double mean, double stddev) {
}
