package com.panopticon.data.stats;

import java.time.Instant;

/** A single duration sample paired with the instant it was recorded. */
public record TimedDuration(long durationMs, Instant at) {
}
