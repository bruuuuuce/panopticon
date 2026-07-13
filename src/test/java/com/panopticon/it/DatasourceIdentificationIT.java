package com.panopticon.it;

import com.panopticon.data.stats.QueryStatsSnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end proof that a panel/data definition is correctly attributed to
 * its own connection, against the app's real dashboards/data definitions
 * from {@code config/} — not the single-datasource it-config fixtures the
 * rest of this package uses. "provider-showcase" is the one bundled
 * dashboard that pulls from every datasource (demo-h2, local-sqlite,
 * secondary-sqlite, demo-jira), including two independent SQLite
 * connections, which is exactly the case a dialect-based (rather than
 * config-identity-based) mix-up would fail on. See
 * {@link DashboardStartupLocationOverrideIT} for the same "boot against real
 * dashboards/data" pattern.
 *
 * <p>{@code local-sqlite}/{@code demo-h2}/{@code demo-jira} all have an
 * explicit {@code display-name} configured; {@code secondary-sqlite}
 * deliberately doesn't, so these assertions also cover the fallback-to-key
 * behavior end to end, not just at the unit level (see DataSourceDefinitionTest).
 *
 * <p>The three jdbc datasources get their own uniquely-named in-memory URLs
 * here (same reasoning as {@code ConfigHotReloadIT}): H2/SQLite named
 * in-memory databases are shared by every connection using the identical URL
 * within the same JVM, not scoped per Spring context, so reusing the literal
 * application.yml URLs would let this context's schema/seed run collide with
 * {@link DashboardStartupLocationOverrideIT}'s (a different cached context,
 * booted separately, but in the same test JVM).
 *
 * <p>Also covers, on the same "provider-showcase" fixture, that the fixed
 * alerting thresholds configured on a panel round-trip through the real
 * dashboard JSON files to the {@code GET /api/dashboards/{id}} response -
 * that's the only contract the frontend's threshold evaluation (see
 * thresholds.js) depends on; the backend never itself evaluates a breach.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DatasourceIdentificationIT {

    @DynamicPropertySource
    static void isolateDatasources(DynamicPropertyRegistry registry) {
        registry.add("panopticon.datasources.demo-h2.jdbc-url",
                () -> "jdbc:h2:mem:datasource-identification-it;DB_CLOSE_DELAY=-1");
        registry.add("panopticon.datasources.local-sqlite.jdbc-url",
                () -> "jdbc:sqlite:file:datasource-identification-it-local?mode=memory&cache=shared");
        registry.add("panopticon.datasources.secondary-sqlite.jdbc-url",
                () -> "jdbc:sqlite:file:datasource-identification-it-secondary?mode=memory&cache=shared");
    }

    @Autowired
    private TestRestTemplate rest;

    private static final Map<String, String> EXPECTED_DATASOURCE_NAME_BY_PANEL = Map.of(
            "kpi-jira-open", "Demo Jira (mocked)",
            "kpi-sqlite-online", "Local SQLite (Edge Nodes)",
            "kpi-secondary-low-stock", "secondary-sqlite"
    );

    @Test
    void panelDataIsAttributedToItsOwnConnection_acrossFourDistinctDatasources() {
        EXPECTED_DATASOURCE_NAME_BY_PANEL.forEach((panelId, expectedDatasourceName) -> {
            ResponseEntity<Map> response = rest.getForEntity(
                    "/api/dashboards/{d}/panels/{p}/data", Map.class, "provider-showcase", panelId);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).as("panel %s", panelId)
                    .containsEntry("datasourceName", expectedDatasourceName);
        });
    }

    @Test
    void twoIndependentSqliteConnectionsAreNeverConfusedWithEachOther() {
        ResponseEntity<Map> local = rest.getForEntity(
                "/api/dashboards/{d}/panels/{p}/data", Map.class, "provider-showcase", "kpi-sqlite-online");
        ResponseEntity<Map> secondary = rest.getForEntity(
                "/api/dashboards/{d}/panels/{p}/data", Map.class, "provider-showcase", "kpi-secondary-low-stock");

        assertThat(local.getBody().get("datasourceName")).isEqualTo("Local SQLite (Edge Nodes)");
        assertThat(secondary.getBody().get("datasourceName")).isEqualTo("secondary-sqlite");
        assertThat(local.getBody().get("datasourceName")).isNotEqualTo(secondary.getBody().get("datasourceName"));
    }

    @Test
    void queryStatsResolvesTheSameDisplayNamesAndKeepsTheTechnicalKeySeparate() {
        ResponseEntity<QueryStatsSnapshot[]> response = rest.getForEntity("/api/query-stats", QueryStatsSnapshot[].class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<QueryStatsSnapshot> snapshots = List.of(response.getBody());

        QueryStatsSnapshot local = findByDataId(snapshots, "local-kpi-online-nodes");
        assertThat(local.datasource()).isEqualTo("local-sqlite");
        assertThat(local.datasourceDisplayName()).isEqualTo("Local SQLite (Edge Nodes)");

        QueryStatsSnapshot secondary = findByDataId(snapshots, "secondary-kpi-low-stock");
        assertThat(secondary.datasource()).isEqualTo("secondary-sqlite");
        // No display-name configured for this one - falls back to the technical key itself.
        assertThat(secondary.datasourceDisplayName()).isEqualTo("secondary-sqlite");

        QueryStatsSnapshot h2 = findByDataId(snapshots, "kpi-open-tickets");
        assertThat(h2.datasource()).isEqualTo("demo-h2");
        assertThat(h2.datasourceDisplayName()).isEqualTo("Demo H2 (Ticketing & Payments)");
    }

    @Test
    void panelThresholdsAreExposedInTheDashboardApiResponse() {
        ResponseEntity<Map> response = rest.getForEntity("/api/dashboards/{d}", Map.class, "provider-showcase");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        List<Map<String, Object>> panels = (List<Map<String, Object>>) response.getBody().get("panels");
        Map<String, Object> lowStockPanel = panels.stream()
                .filter(p -> "kpi-secondary-low-stock".equals(p.get("id")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Panel kpi-secondary-low-stock not found"));

        List<Map<String, Object>> thresholds = (List<Map<String, Object>>) lowStockPanel.get("thresholds");
        assertThat(thresholds).hasSize(1);
        assertThat(thresholds.get(0))
                .containsEntry("field", "low_stock_items")
                .containsEntry("warning", 1.0)
                .containsEntry("critical", 3.0)
                .containsEntry("direction", "above");
    }

    private QueryStatsSnapshot findByDataId(List<QueryStatsSnapshot> snapshots, String dataId) {
        return snapshots.stream().filter(s -> s.dataId().equals(dataId)).findFirst()
                .orElseThrow(() -> new AssertionError("No query-stats entry for dataId " + dataId));
    }
}
