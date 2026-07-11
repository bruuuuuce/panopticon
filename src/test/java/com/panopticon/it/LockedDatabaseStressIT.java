package com.panopticon.it;

import com.panopticon.api.dto.ApiError;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * SQLite locks at the database-file level, not per-table (unlike, say,
 * Postgres row/table locks) - see the discussion that shaped this suite. This
 * simulates a real "another writer is mid-transaction" production condition
 * by holding an uncommitted {@code BEGIN EXCLUSIVE} transaction from a
 * connection outside the app entirely - which, under the plain rollback-
 * journal mode this datasource uses (see AbstractProductionLikeIT.jdbcUrl),
 * blocks every other connection's reads and writes alike, not just other
 * writers - and asserts the whole system degrades cleanly under it: the
 * app-level read fails fast (not hangs) with a clear error, the concurrently-
 * running traffic simulator's writers survive the contention instead of
 * dying, and everything recovers the instant the lock is released.
 */
class LockedDatabaseStressIT extends AbstractProductionLikeIT {

    @Autowired
    private TestRestTemplate rest;

    @Test
    @Timeout(30)
    void concurrentReadFailsCleanlyWhileDatabaseIsExclusivelyLocked_thenFullyRecovers() throws Exception {
        long tickErrorsBefore = simulator.tickErrors();
        long usersBefore = simulator.usersCreated();

        // Acquiring a whole-database EXCLUSIVE lock needs a genuinely quiescent instant - with 5
        // background writer threads touching the file every ~100-150ms, trying to grab it while
        // they're live is flaky (the acquisition itself can lose the race and throw SQLITE_BUSY).
        // Pausing for the acquisition only, then resuming, keeps that one step deterministic while
        // the actual thing under test - writers and readers contending against a held lock - still
        // happens for real, on purpose, right after.
        simulator.pauseWrites();
        Connection lockHolder = DriverManager.getConnection(jdbcUrl());
        try {
            try (Statement st = lockHolder.createStatement()) {
                st.execute("BEGIN EXCLUSIVE");
                st.executeUpdate("INSERT INTO audit_log(actor, action, created_at) VALUES ('lock-test', 'hold', '"
                        + Instant.now() + "')");
            } finally {
                simulator.resumeWrites();
            }

            AtomicReference<ResponseEntity<ApiError>> lastResponse = new AtomicReference<>();
            await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(200)).untilAsserted(() -> {
                ResponseEntity<ApiError> response = rest.getForEntity(
                        "/api/dashboards/{d}/panels/{p}/data", ApiError.class, "fault-injection-lab", "panel-locked-db");
                lastResponse.set(response);
                assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
            });
            assertThat(lastResponse.get().getBody()).isNotNull();
            assertThat(lastResponse.get().getBody().message().toLowerCase(Locale.ROOT))
                    .containsAnyOf("lock", "busy");

            // The background writers hit the exact same contention concurrently; they must be
            // catching it (see ProductionTrafficSimulator's per-tick try/catch), not dying.
            await().atMost(Duration.ofSeconds(5)).until(() -> simulator.tickErrors() > tickErrorsBefore);
        } finally {
            lockHolder.close();
        }

        // Lock released: the app recovers immediately, and the simulator keeps making progress -
        // proving the contention didn't leave the connection pool or a worker thread wedged.
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            ResponseEntity<ApiError> response = rest.getForEntity(
                    "/api/dashboards/{d}/panels/{p}/data", ApiError.class, "fault-injection-lab", "panel-locked-db");
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        });
        await().atMost(Duration.ofSeconds(5)).until(() -> simulator.usersCreated() > usersBefore);
    }
}
