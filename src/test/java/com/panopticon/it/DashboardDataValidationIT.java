package com.panopticon.it;

import com.panopticon.model.DataResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The core "does what the dashboard shows match what's really in the
 * database" suite. Every data definition behind these panels has
 * cacheTtlSeconds=0 (see it-config/data/*.json), so every call is guaranteed
 * fresh - no cache staleness to account for, only the fact that the database
 * is being mutated concurrently by ProductionTrafficSimulator the whole time.
 *
 * <p>Most tests here pause the simulator ({@link #pause()}/{@link #resume()})
 * around the comparison so it's an exact snapshot rather than a race between
 * two independent reads; {@link #eventualConsistencyUnderSustainedLoad_noPause()}
 * deliberately does NOT pause, to prove the dashboard stays correct (not just
 * fast) under genuine concurrent write pressure.
 */
class DashboardDataValidationIT extends AbstractProductionLikeIT {

    @Autowired
    private TestRestTemplate rest;

    @AfterEach
    void alwaysResume() {
        simulator.resumeWrites();
    }

    private void pause() {
        simulator.pauseWrites();
    }

    private void resume() {
        simulator.resumeWrites();
    }

    private DataResult panelData(String dashboardId, String panelId) {
        DataResult result = rest.getForObject(
                "/api/dashboards/{d}/panels/{p}/data", DataResult.class, dashboardId, panelId);
        assertThat(result).isNotNull();
        return result;
    }

    // ---------- user-operations ----------

    @Test
    void activeUsersStatMatchesRawCount() {
        pause();
        DataResult result = panelData("user-operations", "kpi-active-users");
        long rawCount = rawDb.count("SELECT COUNT(*) FROM users WHERE status = 'ACTIVE'");
        resume();

        assertThat(result.status().name()).isEqualTo("OK");
        assertThat(result.rows()).hasSize(1);
        assertThat(asLong(result.rows().get(0).get("active_users"))).isEqualTo(rawCount);
    }

    @Test
    void usersByRoleDonutMatchesRawGroupedCounts() {
        pause();
        DataResult result = panelData("user-operations", "donut-users-by-role");
        List<Map<String, Object>> raw = rawDb.query("SELECT role, COUNT(*) AS user_count FROM users GROUP BY role");
        resume();

        assertThat(keyedByFirstColumn(result.rows(), "role", "user_count"))
                .isEqualTo(keyedByFirstColumn(raw, "role", "user_count"));
    }

    @Test
    void recentUsersTableMatchesRawRowsExactly() {
        pause();
        DataResult result = panelData("user-operations", "table-recent-users");
        List<Map<String, Object>> raw = rawDb.query(
                "SELECT id, username, role, status, created_at FROM users ORDER BY id DESC LIMIT 25");
        resume();

        assertRowsEqual(result.rows(), raw, List.of("id", "username", "role", "status", "created_at"));
    }

    // ---------- support-ticketing ----------

    @Test
    void openTicketsStatMatchesRawCount() {
        pause();
        DataResult result = panelData("support-ticketing", "kpi-open-tickets");
        long rawCount = rawDb.count("SELECT COUNT(*) FROM tickets WHERE status NOT IN ('RESOLVED','CLOSED')");
        resume();

        assertThat(asLong(result.rows().get(0).get("open_tickets"))).isEqualTo(rawCount);
    }

    @Test
    void ticketsByStatusBarMatchesRawGroupedCounts() {
        pause();
        DataResult result = panelData("support-ticketing", "bar-tickets-by-status");
        List<Map<String, Object>> raw = rawDb.query("SELECT status, COUNT(*) AS ticket_count FROM tickets GROUP BY status");
        resume();

        assertThat(keyedByFirstColumn(result.rows(), "status", "ticket_count"))
                .isEqualTo(keyedByFirstColumn(raw, "status", "ticket_count"));
    }

    @Test
    void recentTicketsTableMatchesRawRowsExactly() {
        pause();
        DataResult result = panelData("support-ticketing", "table-recent-tickets");
        List<Map<String, Object>> raw = rawDb.query(
                "SELECT id, subject, status, priority, created_at FROM tickets ORDER BY id DESC LIMIT 25");
        resume();

        assertRowsEqual(result.rows(), raw, List.of("id", "subject", "status", "priority", "created_at"));
    }

    // ---------- transactions-and-events ----------

    @Test
    void settledAmountStatMatchesRawSum() {
        pause();
        DataResult result = panelData("transactions-and-events", "kpi-settled-amount");
        long rawSum = rawDb.count("SELECT COALESCE(SUM(amount_cents), 0) FROM transactions WHERE status = 'SETTLED'");
        resume();

        assertThat(asLong(result.rows().get(0).get("settled_amount_cents"))).isEqualTo(rawSum);
    }

    @Test
    void eventsByStatusDonutMatchesRawGroupedCounts() {
        pause();
        DataResult result = panelData("transactions-and-events", "donut-events-by-status");
        List<Map<String, Object>> raw = rawDb.query("SELECT status, COUNT(*) AS event_count FROM event_outbox GROUP BY status");
        resume();

        assertThat(keyedByFirstColumn(result.rows(), "status", "event_count"))
                .isEqualTo(keyedByFirstColumn(raw, "status", "event_count"));
    }

    // ---------- stress / no pause ----------

    /**
     * Deliberately does not pause the simulator: repeatedly reads the panel via HTTP and the raw
     * table back-to-back while 5 writer threads are actively mutating it, and requires the two to
     * agree on at least one attempt within a bounded window. A dashboard that were silently stale
     * (e.g. serving cached/stuck data) or silently wrong (e.g. a bad WHERE clause) would never
     * converge with a live count, no matter how many attempts.
     */
    @Test
    void eventualConsistencyUnderSustainedLoad_noPause() {
        await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(50)).untilAsserted(() -> {
            DataResult result = panelData("support-ticketing", "kpi-open-tickets");
            long rawCount = rawDb.count("SELECT COUNT(*) FROM tickets WHERE status NOT IN ('RESOLVED','CLOSED')");
            assertThat(asLong(result.rows().get(0).get("open_tickets"))).isEqualTo(rawCount);
        });
    }

    // ---------- helpers ----------

    private static long asLong(Object value) {
        return ((Number) value).longValue();
    }

    private static Map<Object, Long> keyedByFirstColumn(List<Map<String, Object>> rows, String keyField, String valueField) {
        return rows.stream().collect(Collectors.toMap(
                r -> r.get(keyField),
                r -> asLong(r.get(valueField))));
    }

    private static void assertRowsEqual(List<Map<String, Object>> actual, List<Map<String, Object>> expected, List<String> columns) {
        assertThat(actual).hasSize(expected.size());
        for (int i = 0; i < expected.size(); i++) {
            Map<String, Object> a = actual.get(i);
            Map<String, Object> e = expected.get(i);
            for (String column : columns) {
                assertThat(norm(a.get(column)))
                        .as("row %d column '%s'", i, column)
                        .isEqualTo(norm(e.get(column)));
            }
        }
    }

    /** Numeric values can come back as Integer/Long/Double depending on JDBC driver vs. JSON
     *  deserialization boxing; normalize before comparing so that's never a false failure. */
    private static String norm(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Number n) {
            return (value instanceof Double || value instanceof Float) ? String.valueOf(n.doubleValue()) : String.valueOf(n.longValue());
        }
        return String.valueOf(value).toLowerCase(Locale.ROOT);
    }
}
