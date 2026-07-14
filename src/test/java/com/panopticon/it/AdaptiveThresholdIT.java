package com.panopticon.it;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end proof that GET .../panels/{id}/data builds and exposes an
 * {@code adaptiveBaseline} for a 'stat' panel's value field over repeated
 * calls (see AdaptiveThresholdTracker/DashboardController), and never does
 * so for a non-'stat' panel. Boots against the real "provider-showcase"
 * dashboard/data from {@code config/}, same "boot against real
 * dashboards/data" pattern as {@link DatasourceIdentificationIT} — including
 * the same jdbc datasource URL isolation, for the same reason (H2/SQLite
 * named in-memory databases are shared by every connection using the
 * identical URL within the same JVM, not scoped per Spring context, so this
 * context's schema/seed run would otherwise collide with the other
 * real-config-booting IT classes in the same test JVM).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AdaptiveThresholdIT {

    @DynamicPropertySource
    static void isolateDatasources(DynamicPropertyRegistry registry) {
        registry.add("panopticon.datasources.demo-h2.jdbc-url",
                () -> "jdbc:h2:mem:adaptive-threshold-it;DB_CLOSE_DELAY=-1");
        registry.add("panopticon.datasources.local-sqlite.jdbc-url",
                () -> "jdbc:sqlite:file:adaptive-threshold-it-local?mode=memory&cache=shared");
        registry.add("panopticon.datasources.secondary-sqlite.jdbc-url",
                () -> "jdbc:sqlite:file:adaptive-threshold-it-secondary?mode=memory&cache=shared");
    }

    @Autowired
    private TestRestTemplate rest;

    @Test
    void statPanel_getsAnAdaptiveBaselineOnceEnoughSamplesAreRecorded() {
        // "kpi-jira-open" is a 'stat' panel (options.valueField "total") backed by the
        // mocked Jira provider, which returns a stable count with no external I/O -
        // ideal for exercising the sample-count mechanics without depending on real variance.
        for (int i = 0; i < 9; i++) {
            Map<String, Object> body = fetchPanel("kpi-jira-open");
            assertThat(body.get("adaptiveBaseline")).as("call %d, before MIN_SAMPLES", i + 1).isNull();
        }

        Map<String, Object> tenth = fetchPanel("kpi-jira-open");
        assertThat(tenth.get("adaptiveBaseline")).isNotNull();
        @SuppressWarnings("unchecked")
        Map<String, Object> baseline = (Map<String, Object>) tenth.get("adaptiveBaseline");
        assertThat(baseline).containsEntry("sampleCount", 10);
        assertThat(baseline.get("mean")).isNotNull();
        assertThat(baseline.get("stddev")).isNotNull();
    }

    @Test
    void tablePanel_neverGetsAnAdaptiveBaseline() {
        for (int i = 0; i < 12; i++) {
            Map<String, Object> body = fetchPanel("table-sqlite-nodes");
            assertThat(body.get("adaptiveBaseline")).as("call %d", i + 1).isNull();
        }
    }

    private Map<String, Object> fetchPanel(String panelId) {
        ResponseEntity<Map> response = rest.getForEntity(
                "/api/dashboards/{d}/panels/{p}/data", Map.class, "provider-showcase", panelId);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }
}
