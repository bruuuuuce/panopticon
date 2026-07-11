package com.panopticon.data;

import com.panopticon.model.DataResult;
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

    private record Failure(String message) implements Outcome {
    }

    private record Entry(Outcome outcome, Instant expiresAt) {
        boolean isFresh() {
            return Instant.now().isBefore(expiresAt);
        }
    }

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    public DataResult getOrCompute(String dataId, int ttlSeconds, Supplier<DataResult> loader) {
        if (ttlSeconds <= 0) {
            return loader.get();
        }

        Entry cached = entries.get(dataId);
        if (cached != null && cached.isFresh()) {
            return resolve(cached.outcome());
        }

        ReentrantLock lock = locks.computeIfAbsent(dataId, id -> new ReentrantLock());
        lock.lock();
        try {
            // Another thread may have refreshed this entry while we were waiting for the lock.
            cached = entries.get(dataId);
            if (cached != null && cached.isFresh()) {
                return resolve(cached.outcome());
            }
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
            entries.put(dataId, new Entry(new Failure(e.getMessage()), expiresAt));
            throw e;
        }
    }

    private DataResult resolve(Outcome outcome) {
        if (outcome instanceof Success success) {
            return success.result();
        }
        throw new DataExecutionException(((Failure) outcome).message());
    }
}
