package com.panopticon.query;

import com.panopticon.model.QueryResult;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * In-memory, per-query-id result cache with a per-query TTL
 * ({@link com.panopticon.model.QueryDefinition#cacheTtlSeconds()}).
 *
 * <p>This is what lets Panopticon behave like an operational dashboard product
 * rather than a thin SQL proxy: a query id is a single cache key regardless of
 * how many panels/dashboards reference it via {@code queryRef}, or how many
 * viewers have a dashboard open at once, so a burst of concurrent panel
 * refreshes collapses into one datasource hit. A per-key lock (rather than a
 * single global lock) means an expired entry for one query doesn't block
 * refreshes of unrelated queries while it's being recomputed.
 *
 * <p>A failing query is cached too (as a {@link Failure}), for the same TTL
 * as a success would get. Without this, a datasource outage or a broken
 * query would defeat the entire point of the cache: every viewer's refresh
 * would re-hit the failing datasource instead of being collapsed like a
 * healthy query is. The cached failure is replayed as a fresh
 * {@link QueryExecutionException} on every cache hit until the TTL expires
 * and the next request gets to try again.
 */
@Component
public class QueryResultCache {

    private sealed interface Outcome permits Success, Failure {
    }

    private record Success(QueryResult result) implements Outcome {
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

    public QueryResult getOrCompute(String queryId, int ttlSeconds, Supplier<QueryResult> loader) {
        if (ttlSeconds <= 0) {
            return loader.get();
        }

        Entry cached = entries.get(queryId);
        if (cached != null && cached.isFresh()) {
            return resolve(cached.outcome());
        }

        ReentrantLock lock = locks.computeIfAbsent(queryId, id -> new ReentrantLock());
        lock.lock();
        try {
            // Another thread may have refreshed this entry while we were waiting for the lock.
            cached = entries.get(queryId);
            if (cached != null && cached.isFresh()) {
                return resolve(cached.outcome());
            }
            return computeAndCache(queryId, ttlSeconds, loader);
        } finally {
            lock.unlock();
        }
    }

    private QueryResult computeAndCache(String queryId, int ttlSeconds, Supplier<QueryResult> loader) {
        Instant expiresAt = Instant.now().plusSeconds(ttlSeconds);
        try {
            QueryResult fresh = loader.get();
            entries.put(queryId, new Entry(new Success(fresh), expiresAt));
            return fresh;
        } catch (QueryExecutionException e) {
            entries.put(queryId, new Entry(new Failure(e.getMessage()), expiresAt));
            throw e;
        }
    }

    private QueryResult resolve(Outcome outcome) {
        if (outcome instanceof Success success) {
            return success.result();
        }
        throw new QueryExecutionException(((Failure) outcome).message(), null);
    }
}
