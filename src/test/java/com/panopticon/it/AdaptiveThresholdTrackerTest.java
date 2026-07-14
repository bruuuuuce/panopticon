package com.panopticon.it;

import com.panopticon.data.stats.AdaptiveThresholdTracker;
import com.panopticon.model.AdaptiveBaseline;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * No Spring context needed: AdaptiveThresholdTracker is a plain thread-safe
 * component, exercised directly with hand-built samples whose exact
 * mean/stddev are known ahead of time. The actual z-score/level decision is
 * frontend logic (thresholds.js, no JS test runner in this repo) - what
 * matters here is that the raw numbers it hands to that decision are right.
 */
class AdaptiveThresholdTrackerTest {

    private final AdaptiveThresholdTracker tracker = new AdaptiveThresholdTracker();

    @Test
    void baseline_isNullBelowTenSamples() {
        for (int i = 0; i < 9; i++) {
            tracker.record("d1", "n", 50.0);
        }
        assertThat(tracker.baseline("d1", "n")).isNull();
    }

    @Test
    void baseline_appearsAtTenSamples() {
        for (int i = 0; i < 10; i++) {
            tracker.record("d1", "n", 50.0);
        }
        assertThat(tracker.baseline("d1", "n")).isNotNull();
    }

    @Test
    void meanAndStddev_computedFromKnownSamples() {
        // 2,4,4,4,5,5,7,9 -> mean 5, sample stddev 2.138... (textbook example)
        double[] values = {2, 4, 4, 4, 5, 5, 7, 9, 5, 5};
        for (double v : values) {
            tracker.record("d1", "n", v);
        }
        AdaptiveBaseline baseline = tracker.baseline("d1", "n");

        assertThat(baseline).isNotNull();
        assertThat(baseline.sampleCount()).isEqualTo(10);
        assertThat(baseline.mean()).isCloseTo(5.0, within(0.01));
        assertThat(baseline.stddev()).isCloseTo(1.885, within(0.01));
    }

    @Test
    void constantSeries_hasZeroStddev() {
        for (int i = 0; i < 15; i++) {
            tracker.record("d1", "n", 42.0);
        }
        AdaptiveBaseline baseline = tracker.baseline("d1", "n");

        assertThat(baseline.mean()).isEqualTo(42.0);
        assertThat(baseline.stddev()).isEqualTo(0.0);
    }

    @Test
    void nullAndNonFiniteValues_areNotRecorded() {
        for (int i = 0; i < 10; i++) {
            tracker.record("d1", "n", 50.0);
        }
        tracker.record("d1", "n", null);
        tracker.record("d1", "n", Double.NaN);
        tracker.record("d1", "n", Double.POSITIVE_INFINITY);

        assertThat(tracker.baseline("d1", "n").sampleCount()).isEqualTo(10);
    }

    @Test
    void differentDataIdsAndFields_areIndependentSeries() {
        for (int i = 0; i < 10; i++) {
            tracker.record("d1", "n", 10.0);
            tracker.record("d1", "m", 20.0);
            tracker.record("d2", "n", 30.0);
        }

        assertThat(tracker.baseline("d1", "n").mean()).isEqualTo(10.0);
        assertThat(tracker.baseline("d1", "m").mean()).isEqualTo(20.0);
        assertThat(tracker.baseline("d2", "n").mean()).isEqualTo(30.0);
    }

    @Test
    void reservoirOverwritesOldestSampleBeyondCapacity() {
        // Capacity is 200: fill it with 100, then overwrite all with 200, then add ten
        // 300s - the 100s must be long gone, leaving 190 samples of 200 and 10 of 300.
        for (int i = 0; i < 200; i++) {
            tracker.record("d1", "n", 100.0);
        }
        for (int i = 0; i < 190; i++) {
            tracker.record("d1", "n", 200.0);
        }
        for (int i = 0; i < 10; i++) {
            tracker.record("d1", "n", 300.0);
        }

        AdaptiveBaseline baseline = tracker.baseline("d1", "n");
        assertThat(baseline.sampleCount()).isEqualTo(200);
        // (190*200 + 10*300) / 200 = 205
        assertThat(baseline.mean()).isCloseTo(205.0, within(0.01));
    }

    @Test
    void unknownSeries_returnsNullBaseline() {
        assertThat(tracker.baseline("never-seen", "n")).isNull();
    }
}
