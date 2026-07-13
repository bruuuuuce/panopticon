package com.panopticon.data.stats;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.atomic.LongAdder;

/**
 * In-memory, per-{@code dataId} execution statistics backing the "query
 * stats" ops view: total executions/errors, last execution snapshot, and
 * avg/min/max/p95/p99 duration — each extreme (min/max/p95/p99) paired with
 * the {@link Instant} it happened, so an operator can tell not just "how bad"
 * but "when". Only real provider executions (cache misses) are recorded —
 * the same choke point {@code DataEngine} already times with Micrometer —
 * because the point of this view is to see load actually placed on a
 * datasource, not traffic served from cache.
 *
 * <p>Resets on restart, like the rest of Panopticon's in-memory metrics
 * (Micrometer's default {@code SimpleMeterRegistry}); this is a deliberate
 * scope choice, not an oversight — see the "Query stats" section of the
 * README. {@code avg}/{@code min}/{@code max} are exact over the whole
 * process lifetime (cheap running aggregates); {@code p95}/{@code p99} are
 * computed from a bounded reservoir of the most recent samples per
 * {@code dataId}, so they're exact for that recent window and approximate
 * over the full lifetime — the right tradeoff for "is this stressing the
 * environment right now" rather than an audit log.
 */
@Component
public class QueryExecutionStatsTracker {

    private static final int RESERVOIR_CAPACITY = 500;

    private final Map<String, PerQueryStats> byDataId = new ConcurrentHashMap<>();

    public void recordSuccess(String dataId, long durationMs, int rowCount, Instant at) {
        statsFor(dataId).recordSuccess(durationMs, rowCount, at);
    }

    public void recordFailure(String dataId, long durationMs, Instant at) {
        statsFor(dataId).recordFailure(durationMs, at);
    }

    public QueryStatsSnapshot snapshot(String dataId, String name, String provider, String datasource, String datasourceDisplayName) {
        PerQueryStats stats = byDataId.get(dataId);
        return stats == null
                ? QueryStatsSnapshot.neverExecuted(dataId, name, provider, datasource, datasourceDisplayName)
                : stats.snapshot(dataId, name, provider, datasource, datasourceDisplayName);
    }

    private PerQueryStats statsFor(String dataId) {
        return byDataId.computeIfAbsent(dataId, id -> new PerQueryStats());
    }

    private static final class PerQueryStats {
        private final LongAdder successCount = new LongAdder();
        private final LongAdder errorCount = new LongAdder();
        private final AtomicLong durationSumMs = new AtomicLong();
        private final LongAdder durationSampleCount = new LongAdder();
        private final AtomicReference<TimedDuration> min = new AtomicReference<>();
        private final AtomicReference<TimedDuration> max = new AtomicReference<>();
        private final AtomicReference<LastExecution> last = new AtomicReference<>();
        // Circular reservoir of immutable samples: writes are single atomic
        // reference stores (no torn reads), reads take a point-in-time copy.
        private final AtomicReferenceArray<TimedDuration> reservoir = new AtomicReferenceArray<>(RESERVOIR_CAPACITY);
        private final AtomicInteger cursor = new AtomicInteger();

        private record LastExecution(Instant at, long durationMs, int rowCount, String status) {
        }

        void recordSuccess(long durationMs, int rowCount, Instant at) {
            successCount.increment();
            durationSumMs.addAndGet(durationMs);
            durationSampleCount.increment();
            updateExtreme(min, durationMs, at, false);
            updateExtreme(max, durationMs, at, true);
            addSample(durationMs, at);
            last.set(new LastExecution(at, durationMs, rowCount, "OK"));
        }

        void recordFailure(long durationMs, Instant at) {
            errorCount.increment();
            last.set(new LastExecution(at, durationMs, 0, "ERROR"));
        }

        private void updateExtreme(AtomicReference<TimedDuration> ref, long durationMs, Instant at, boolean wantMax) {
            TimedDuration candidate = new TimedDuration(durationMs, at);
            ref.accumulateAndGet(candidate, (current, next) -> {
                if (current == null) {
                    return next;
                }
                boolean better = wantMax
                        ? next.durationMs() > current.durationMs()
                        : next.durationMs() < current.durationMs();
                return better ? next : current;
            });
        }

        private void addSample(long durationMs, Instant at) {
            int idx = Math.floorMod(cursor.getAndIncrement(), RESERVOIR_CAPACITY);
            reservoir.set(idx, new TimedDuration(durationMs, at));
        }

        QueryStatsSnapshot snapshot(String dataId, String name, String provider, String datasource, String datasourceDisplayName) {
            LastExecution lastExec = last.get();
            TimedDuration minVal = min.get();
            TimedDuration maxVal = max.get();
            long samples = durationSampleCount.sum();
            Double avg = samples == 0 ? null : (double) durationSumMs.get() / samples;

            TimedDuration[] sorted = sortedSamples();
            TimedDuration p95 = percentile(sorted, 0.95);
            TimedDuration p99 = percentile(sorted, 0.99);

            return new QueryStatsSnapshot(
                    dataId, name, provider, datasource, datasourceDisplayName,
                    lastExec == null ? null : lastExec.at(),
                    lastExec == null ? null : lastExec.durationMs(),
                    lastExec == null ? null : lastExec.rowCount(),
                    lastExec == null ? null : lastExec.status(),
                    successCount.sum() + errorCount.sum(),
                    errorCount.sum(),
                    avg,
                    minVal == null ? null : minVal.durationMs(), minVal == null ? null : minVal.at(),
                    maxVal == null ? null : maxVal.durationMs(), maxVal == null ? null : maxVal.at(),
                    p95 == null ? null : p95.durationMs(), p95 == null ? null : p95.at(),
                    p99 == null ? null : p99.durationMs(), p99 == null ? null : p99.at());
        }

        private TimedDuration[] sortedSamples() {
            TimedDuration[] copy = new TimedDuration[RESERVOIR_CAPACITY];
            int n = 0;
            for (int i = 0; i < RESERVOIR_CAPACITY; i++) {
                TimedDuration sample = reservoir.get(i);
                if (sample != null) {
                    copy[n++] = sample;
                }
            }
            TimedDuration[] trimmed = Arrays.copyOf(copy, n);
            Arrays.sort(trimmed, Comparator.comparingLong(TimedDuration::durationMs));
            return trimmed;
        }

        /** Nearest-rank percentile: rank = ceil(p * n), 1-indexed, clamped into bounds. */
        private TimedDuration percentile(TimedDuration[] sorted, double p) {
            if (sorted.length == 0) {
                return null;
            }
            int rank = (int) Math.ceil(p * sorted.length) - 1;
            rank = Math.max(0, Math.min(sorted.length - 1, rank));
            return sorted[rank];
        }
    }
}
