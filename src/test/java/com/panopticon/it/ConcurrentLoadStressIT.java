package com.panopticon.it;

import com.panopticon.data.DataEngine;
import com.panopticon.model.DashboardDefinition;
import com.panopticon.model.PanelDefinition;
import com.panopticon.registry.DashboardRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * General concurrency stress on top of the always-on background traffic:
 * proves {@link com.panopticon.data.DataResultCache} genuinely collapses a
 * concurrent burst of refreshes into one provider execution (not just "the
 * values happen to match"), and hammers every panel across every fixture
 * dashboard - including the fault-injection ones - from many threads at once
 * to prove nothing leaks an unhandled exception under load, regardless of
 * whether the underlying data condition is healthy or broken.
 */
class ConcurrentLoadStressIT extends AbstractProductionLikeIT {

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private DataEngine dataEngine;

    @Autowired
    private DashboardRegistry dashboardRegistry;

    @Test
    @Timeout(20)
    void concurrentRefreshesOfACachedDataDefinitionCollapseIntoOneExecution() throws Exception {
        int threadCount = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Instant>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threadCount; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return dataEngine.execute("tickets-count-cached").generatedAt();
                }));
            }
            ready.await();
            go.countDown();

            Set<Instant> distinctExecutionTimestamps = new HashSet<>();
            for (Future<Instant> future : futures) {
                distinctExecutionTimestamps.add(future.get(10, TimeUnit.SECONDS));
            }

            // 20 concurrent callers, one underlying provider execution: every result carries the
            // exact same generatedAt because 19 of them were served from cache, not re-executed.
            assertThat(distinctExecutionTimestamps).hasSize(1);
        } finally {
            pool.shutdown();
        }
    }

    @Test
    @Timeout(60)
    void sustainedConcurrentMixedLoadAcrossEveryPanel_neverLeaksAnUnhandledException() throws Exception {
        List<String[]> targets = new ArrayList<>();
        for (DashboardDefinition dashboard : dashboardRegistry.all()) {
            for (PanelDefinition panel : dashboard.panels()) {
                targets.add(new String[]{dashboard.id(), panel.id()});
            }
        }
        assertThat(targets).isNotEmpty();

        int threadCount = 16;
        int iterationsPerThread = 15;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        Random seedRandom = new Random(7);
        List<String> unexpected = Collections.synchronizedList(new ArrayList<>());
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int t = 0; t < threadCount; t++) {
                Random threadRandom = new Random(seedRandom.nextLong());
                futures.add(pool.submit(() -> {
                    for (int i = 0; i < iterationsPerThread; i++) {
                        String[] target = targets.get(threadRandom.nextInt(targets.size()));
                        ResponseEntity<String> response = rest.getForEntity(
                                "/api/dashboards/{d}/panels/{p}/data", String.class, target[0], target[1]);
                        HttpStatus status = HttpStatus.valueOf(response.getStatusCode().value());
                        boolean knownStatus = status == HttpStatus.OK || status == HttpStatus.BAD_GATEWAY
                                || status == HttpStatus.NOT_FOUND || status == HttpStatus.BAD_REQUEST;
                        boolean leakedInternalError = response.getBody() != null && response.getBody().contains("internal_error");
                        if (!knownStatus || leakedInternalError) {
                            unexpected.add(target[0] + "/" + target[1] + " -> " + status + ": " + response.getBody());
                        }
                    }
                }));
            }
            for (Future<?> future : futures) {
                future.get(45, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdown();
        }

        assertThat(unexpected).isEmpty();
    }
}
