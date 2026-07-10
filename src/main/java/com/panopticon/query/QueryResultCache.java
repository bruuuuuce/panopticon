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
 */
@Component
public class QueryResultCache {

    private record Entry(QueryResult result, Instant expiresAt) {
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
            return cached.result();
        }

        ReentrantLock lock = locks.computeIfAbsent(queryId, id -> new ReentrantLock());
        lock.lock();
        try {
            // Another thread may have refreshed this entry while we were waiting for the lock.
            cached = entries.get(queryId);
            if (cached != null && cached.isFresh()) {
                return cached.result();
            }
            QueryResult fresh = loader.get();
            entries.put(queryId, new Entry(fresh, Instant.now().plusSeconds(ttlSeconds)));
            return fresh;
        } finally {
            lock.unlock();
        }
    }
}
