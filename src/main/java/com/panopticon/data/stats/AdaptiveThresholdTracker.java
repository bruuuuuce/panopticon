package com.panopticon.data.stats;

import com.panopticon.model.AdaptiveBaseline;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * In-memory, per-(dataId, field) recent history backing adaptive threshold
 * detection: {@code DashboardController} records every {@code stat} panel
 * value it serves, and this tracker turns the recent window into a mean and
 * sample standard deviation for {@code thresholds.js} to compare the current
 * value against (z-score). Same recent-window/reset-on-restart tradeoff as
 * {@link QueryExecutionStatsTracker}'s p95/p99 reservoir, just keyed by field
 * instead of duration and sized for "recent behavior of one KPI" rather than
 * "recent load on a datasource" — a shorter capacity is enough since the
 * point is the metric's current normal range, not months of trend.
 */
@Component
public class AdaptiveThresholdTracker {

    private static final int RESERVOIR_CAPACITY = 200;
    /** Below this many samples, mean/stddev are too noisy to be a useful baseline. */
    private static final int MIN_SAMPLES = 10;

    private final Map<SeriesKey, Reservoir> byField = new ConcurrentHashMap<>();

    private record SeriesKey(String dataId, String field) {
    }

    public void record(String dataId, String field, Double value) {
        if (value == null || value.isNaN() || value.isInfinite()) {
            return;
        }
        reservoirFor(dataId, field).add(value);
    }

    public AdaptiveBaseline baseline(String dataId, String field) {
        Reservoir reservoir = byField.get(new SeriesKey(dataId, field));
        return reservoir == null ? null : reservoir.baseline();
    }

    private Reservoir reservoirFor(String dataId, String field) {
        return byField.computeIfAbsent(new SeriesKey(dataId, field), key -> new Reservoir());
    }

    private static final class Reservoir {
        // Circular reservoir of boxed values: writes are single atomic reference
        // stores (no torn reads), reads take a point-in-time copy before computing
        // (same pattern as QueryExecutionStatsTracker.sortedSamples()) so a
        // concurrent write mid-computation can't skew mean/stddev inconsistently.
        private final AtomicReferenceArray<Double> samples = new AtomicReferenceArray<>(RESERVOIR_CAPACITY);
        private final AtomicInteger cursor = new AtomicInteger();

        void add(double value) {
            int idx = Math.floorMod(cursor.getAndIncrement(), RESERVOIR_CAPACITY);
            samples.set(idx, value);
        }

        AdaptiveBaseline baseline() {
            Double[] copy = new Double[RESERVOIR_CAPACITY];
            int n = 0;
            for (int i = 0; i < RESERVOIR_CAPACITY; i++) {
                Double v = samples.get(i);
                if (v != null) {
                    copy[n++] = v;
                }
            }
            if (n < MIN_SAMPLES) {
                return null;
            }
            double sum = 0;
            for (int i = 0; i < n; i++) {
                sum += copy[i];
            }
            double mean = sum / n;
            double sumSquaredDiff = 0;
            for (int i = 0; i < n; i++) {
                double diff = copy[i] - mean;
                sumSquaredDiff += diff * diff;
            }
            // Sample standard deviation (n-1 denominator): the reservoir is a sample
            // of the metric's behavior, not its entire population.
            double stddev = Math.sqrt(sumSquaredDiff / (n - 1));
            return new AdaptiveBaseline(n, mean, stddev);
        }
    }
}
