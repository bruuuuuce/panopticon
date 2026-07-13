package com.panopticon.it;

import com.panopticon.api.dto.ApiError;
import com.panopticon.data.stats.QueryStatsSnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end coverage of the "query stats" ops endpoints, through the real
 * HTTP path (DashboardController -> DataEngine -> JdbcDataProvider ->
 * QueryExecutionStatsTracker), against the shared production-like SQLite
 * database. "users-kpi-active-users" has cacheTtlSeconds=0 (see its data
 * definition), so every panel fetch is a real, tracked execution — exactly
 * what's needed to assert on totalExecutions deterministically.
 */
class QueryStatsIT extends AbstractProductionLikeIT {

    @Autowired
    private TestRestTemplate rest;

    @Test
    void queryStats_reflectRealExecutionsOfATrackedDataDefinition() {
        for (int i = 0; i < 3; i++) {
            ResponseEntity<Void> response = rest.getForEntity(
                    "/api/dashboards/{d}/panels/{p}/data", Void.class, "user-operations", "kpi-active-users");
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        ResponseEntity<QueryStatsSnapshot[]> response = rest.getForEntity("/api/query-stats", QueryStatsSnapshot[].class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<QueryStatsSnapshot> snapshots = List.of(response.getBody());

        QueryStatsSnapshot stats = snapshots.stream()
                .filter(s -> s.dataId().equals("users-kpi-active-users"))
                .findFirst().orElseThrow();
        assertThat(stats.provider()).isEqualTo("jdbc");
        assertThat(stats.datasource()).isEqualTo("prod-like-sqlite");
        assertThat(stats.totalExecutions()).isGreaterThanOrEqualTo(3);
        assertThat(stats.totalErrors()).isZero();
        assertThat(stats.lastStatus()).isEqualTo("OK");
        assertThat(stats.lastRowCount()).isEqualTo(1);
        assertThat(stats.avgDurationMs()).isNotNull();
        assertThat(stats.minDurationMs()).isNotNull();
        assertThat(stats.maxDurationMs()).isNotNull();
        assertThat(stats.p95DurationMs()).isNotNull();
        assertThat(stats.p99DurationMs()).isNotNull();
        assertThat(stats.lastExecutedAt()).isNotNull();
    }

    @Test
    void everyLoadedDataDefinitionAppearsInStatsEvenIfNeverExecuted() {
        ResponseEntity<QueryStatsSnapshot[]> response = rest.getForEntity("/api/query-stats", QueryStatsSnapshot[].class);
        List<QueryStatsSnapshot> snapshots = List.of(response.getBody());

        // Catalog metadata (name/provider/datasource) must be present for every configured
        // data definition regardless of whether it has ever actually run.
        assertThat(snapshots).isNotEmpty();
        assertThat(snapshots).allSatisfy(s -> {
            assertThat(s.dataId()).isNotBlank();
            assertThat(s.provider()).isNotBlank();
            assertThat(s.datasource()).isNotBlank();
        });
    }

    @Test
    void planEndpoint_returnsSqlitePlanForARealJdbcDataDefinition() {
        ResponseEntity<com.panopticon.data.plan.QueryPlanResult> response = rest.getForEntity(
                "/api/query-stats/{id}/plan", com.panopticon.data.plan.QueryPlanResult.class, "users-kpi-active-users");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().supported()).isTrue();
        assertThat(response.getBody().planRows()).isNotEmpty();
    }

    @Test
    void planEndpoint_returns404ForUnknownDataId() {
        ResponseEntity<ApiError> response = rest.getForEntity(
                "/api/query-stats/{id}/plan", ApiError.class, "this-data-id-does-not-exist");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
