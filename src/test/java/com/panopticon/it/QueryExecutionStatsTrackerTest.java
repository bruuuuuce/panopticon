package com.panopticon.it;

import com.panopticon.data.stats.QueryExecutionStatsTracker;
import com.panopticon.data.stats.QueryStatsSnapshot;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * No Spring context needed: QueryExecutionStatsTracker is a plain
 * thread-safe component, exercised directly with hand-built samples whose
 * exact avg/min/max/percentile values are known ahead of time.
 */
class QueryExecutionStatsTrackerTest {

    private final QueryExecutionStatsTracker tracker = new QueryExecutionStatsTracker();

    @Test
    void neverExecuted_returnsNullStatsButCarriesCatalogMetadata() {
        QueryStatsSnapshot snapshot = tracker.snapshot("never-run", "Never Run", "jdbc", "demo-h2", "Demo H2 Database");

        assertThat(snapshot.dataId()).isEqualTo("never-run");
        assertThat(snapshot.name()).isEqualTo("Never Run");
        assertThat(snapshot.datasource()).isEqualTo("demo-h2");
        assertThat(snapshot.datasourceDisplayName()).isEqualTo("Demo H2 Database");
        assertThat(snapshot.totalExecutions()).isZero();
        assertThat(snapshot.totalErrors()).isZero();
        assertThat(snapshot.lastExecutedAt()).isNull();
        assertThat(snapshot.avgDurationMs()).isNull();
        assertThat(snapshot.minDurationMs()).isNull();
        assertThat(snapshot.maxDurationMs()).isNull();
        assertThat(snapshot.p95DurationMs()).isNull();
        assertThat(snapshot.p99DurationMs()).isNull();
    }

    @Test
    void avgMinMaxAndPercentiles_computedFromKnownSamples() {
        Instant base = Instant.parse("2026-07-10T00:00:00Z");
        // Durations 1..100 ms, each at a distinct, increasing instant.
        for (int i = 1; i <= 100; i++) {
            tracker.recordSuccess("q1", i, i, base.plus(i, ChronoUnit.SECONDS));
        }

        QueryStatsSnapshot snapshot = tracker.snapshot("q1", "Q1", "jdbc", "demo-h2", "Demo H2 Database");

        assertThat(snapshot.totalExecutions()).isEqualTo(100);
        assertThat(snapshot.totalErrors()).isZero();
        assertThat(snapshot.avgDurationMs()).isEqualTo(50.5);
        assertThat(snapshot.minDurationMs()).isEqualTo(1);
        assertThat(snapshot.minAt()).isEqualTo(base.plus(1, ChronoUnit.SECONDS));
        assertThat(snapshot.maxDurationMs()).isEqualTo(100);
        assertThat(snapshot.maxAt()).isEqualTo(base.plus(100, ChronoUnit.SECONDS));
        // Nearest-rank on 100 sorted samples 1..100: p95 -> 95th smallest, p99 -> 99th smallest.
        assertThat(snapshot.p95DurationMs()).isEqualTo(95);
        assertThat(snapshot.p95At()).isEqualTo(base.plus(95, ChronoUnit.SECONDS));
        assertThat(snapshot.p99DurationMs()).isEqualTo(99);
        assertThat(snapshot.p99At()).isEqualTo(base.plus(99, ChronoUnit.SECONDS));
        assertThat(snapshot.lastExecutedAt()).isEqualTo(base.plus(100, ChronoUnit.SECONDS));
        assertThat(snapshot.lastDurationMs()).isEqualTo(100);
        assertThat(snapshot.lastRowCount()).isEqualTo(100);
        assertThat(snapshot.lastStatus()).isEqualTo("OK");
    }

    @Test
    void reservoirWraparound_keepsLifetimeMinMaxButWindowsPercentiles() {
        Instant base = Instant.parse("2026-07-10T00:00:00Z");
        // 501 samples, capacity 500: the very first sample (duration=1) is evicted from the
        // reservoir, but lifetime min/avg must still reflect the full 501-sample history.
        for (int i = 1; i <= 501; i++) {
            tracker.recordSuccess("q2", i, 1, base.plus(i, ChronoUnit.SECONDS));
        }

        QueryStatsSnapshot snapshot = tracker.snapshot("q2", "Q2", "jdbc", "demo-h2", "Demo H2 Database");

        assertThat(snapshot.totalExecutions()).isEqualTo(501);
        assertThat(snapshot.minDurationMs()).isEqualTo(1); // lifetime min, unaffected by eviction
        assertThat(snapshot.maxDurationMs()).isEqualTo(501);
        double expectedAvg = (1 + 501) * 501 / 2.0 / 501; // arithmetic series mean = 251.0
        assertThat(snapshot.avgDurationMs()).isEqualTo(expectedAvg);
        // Reservoir now holds samples 2..501 (500 values): p99 nearest-rank -> 495th of those (index 494) = 496.
        assertThat(snapshot.p99DurationMs()).isEqualTo(496);
    }

    @Test
    void successAndErrorCountsAreSeparate_andFailuresDoNotPolluteDurationStats() {
        Instant t0 = Instant.parse("2026-07-10T00:00:00Z");
        tracker.recordSuccess("q3", 10, 5, t0);
        tracker.recordFailure("q3", 999, t0.plusSeconds(1));
        tracker.recordFailure("q3", 999, t0.plusSeconds(2));

        QueryStatsSnapshot snapshot = tracker.snapshot("q3", "Q3", "jdbc", "demo-h2", "Demo H2 Database");

        assertThat(snapshot.totalExecutions()).isEqualTo(3);
        assertThat(snapshot.totalErrors()).isEqualTo(2);
        // Only the one successful execution contributes to avg/min/max - the 999ms failures don't.
        assertThat(snapshot.avgDurationMs()).isEqualTo(10.0);
        assertThat(snapshot.minDurationMs()).isEqualTo(10);
        assertThat(snapshot.maxDurationMs()).isEqualTo(10);
        // The last execution overall was a failure.
        assertThat(snapshot.lastStatus()).isEqualTo("ERROR");
        assertThat(snapshot.lastExecutedAt()).isEqualTo(t0.plusSeconds(2));
        assertThat(snapshot.lastDurationMs()).isEqualTo(999);
    }

    @Test
    void independentDataIdsDoNotShareState() {
        tracker.recordSuccess("a", 5, 1, Instant.now());
        tracker.recordSuccess("b", 500, 1, Instant.now());

        assertThat(tracker.snapshot("a", "A", "jdbc", "ds", "ds").maxDurationMs()).isEqualTo(5);
        assertThat(tracker.snapshot("b", "B", "jdbc", "ds", "ds").maxDurationMs()).isEqualTo(500);
    }
}
