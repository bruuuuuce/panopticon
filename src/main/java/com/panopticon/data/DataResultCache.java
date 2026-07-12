package com.panopticon.data;

import com.panopticon.model.DataResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * In-memory, per-data-id result cache with a per-definition TTL
 * ({@link com.panopticon.model.DataDefinition#cacheTtlSeconds()}).
 *
 * <p>This is what lets Panopticon behave like an operational dashboard product
 * rather than a thin data proxy: a data id is a single cache key regardless of
 * how many panels/dashboards reference it via {@code dataRef}, or how many
 * viewers have a dashboard open at once, so a burst of concurrent panel
 * refreshes collapses into one provider execution — for any provider type,
 * not just JDBC. A per-key lock (rather than a single global lock) means an
 * expired entry for one data id doesn't block refreshes of unrelated ones
 * while it's being recomputed.
 *
 * <p>A failing execution is cached too (as a {@link Failure}), for the same
 * TTL as a success would get. Without this, a datasource/upstream outage
 * would defeat the entire point of the cache: every viewer's refresh would
 * re-hit the already-struggling provider instead of being collapsed like a
 * healthy one is. Any {@link RuntimeException} a provider (or the
 * {@link DataEngine} wrapper around it) throws is cached this way — the
 * cache doesn't need to know which provider or exception type is involved,
 * only that the supplier failed. The cached failure is replayed as a fresh
 * {@link DataExecutionException} on every cache hit until the TTL expires
 * and the next request gets to try again.
 */
@Component
public class DataResultCache {

    private sealed interface Outcome permits Success, Failure {
    }

    private record Success(DataResult result) implements Outcome {
    }

    private record Failure(RuntimeException exception) implements Outcome {
    }

    private record Entry(Outcome outcome, Instant expiresAt) {
        boolean isFresh() {
            return Instant.now().isBefore(expiresAt);
        }
    }

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();
    private final MeterRegistry meterRegistry;

    public DataResultCache(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public DataResult getOrCompute(String dataId, int ttlSeconds, Supplier<DataResult> loader) {
        if (ttlSeconds <= 0) {
            count("bypass");
            return loader.get();
        }

        Entry cached = entries.get(dataId);
        if (cached != null && cached.isFresh()) {
            return resolveCounted(cached.outcome());
        }

        ReentrantLock lock = locks.computeIfAbsent(dataId, id -> new ReentrantLock());
        lock.lock();
        try {
            // Another thread may have refreshed this entry while we were waiting for the lock.
            cached = entries.get(dataId);
            if (cached != null && cached.isFresh()) {
                return resolveCounted(cached.outcome());
            }
            count("miss");
            return computeAndCache(dataId, ttlSeconds, loader);
        } finally {
            lock.unlock();
        }
    }

    private DataResult computeAndCache(String dataId, int ttlSeconds, Supplier<DataResult> loader) {
        Instant expiresAt = Instant.now().plusSeconds(ttlSeconds);
        try {
            DataResult fresh = loader.get();
            entries.put(dataId, new Entry(new Success(fresh), expiresAt));
            return fresh;
        } catch (RuntimeException e) {
            entries.put(dataId, new Entry(new Failure(e), expiresAt));
            throw e;
        }
    }

    private DataResult resolveCounted(Outcome outcome) {
        count(outcome instanceof Success ? "hit" : "negative-hit");
        if (outcome instanceof Success success) {
            return success.result();
        }
        // Chain the original as cause: a replayed failure must not degrade to a
        // message-only exception (which could even be null) with no stack trace
        // pointing at what actually broke.
        RuntimeException original = ((Failure) outcome).exception();
        String message = original.getMessage() != null ? original.getMessage() : original.getClass().getSimpleName();
        throw new DataExecutionException(message, original);
    }

    /** Drops every cached entry. Used by config hot-reload: definitions may have changed under the same ids. */
    public void clear() {
        entries.clear();
    }

    private void count(String result) {
        Counter.builder("panopticon.cache")
                .description("Result cache lookups by outcome (hit/negative-hit/miss/bypass)")
                .tag("result", result)
                .register(meterRegistry)
                .increment();
    }
}
