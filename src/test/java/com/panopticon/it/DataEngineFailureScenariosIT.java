package com.panopticon.it;

import com.panopticon.api.dto.ApiError;
import com.panopticon.data.DataEngine;
import com.panopticon.data.DataExecutionException;
import com.panopticon.data.DataProviderRegistry;
import com.panopticon.data.DataResultCache;
import com.panopticon.data.UnknownDataException;
import com.panopticon.data.UnsupportedProviderException;
import com.panopticon.model.DataDefinition;
import com.panopticon.model.DataResult;
import com.panopticon.registry.DataRegistry;
import com.panopticon.registry.DataSourceRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * "100% use case" coverage of how a dashboard panel behaves when its
 * underlying data isn't what it expects: a missing table, an empty table (both
 * "never populated" and "populated but filtered to nothing"), a table another
 * service renamed a column on out from under it, a result bigger than
 * maxRows, and options that reference fields the query doesn't return. All
 * exercised through fault-injection-lab's real dashboard/panel HTTP path
 * (DashboardController -> DataEngine -> JdbcDataProvider), not by calling
 * DataEngine directly, per the request that this cover "una visualizzazione
 * della dashboard" hitting these conditions - plus a handful of DataEngine/
 * registry-level error paths that a config file can never reach (since
 * ConfigValidator rejects them before the app would ever start), covered
 * directly against hand-built definitions instead.
 */
class DataEngineFailureScenariosIT extends AbstractProductionLikeIT {

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private DataEngine dataEngine;

    @Autowired
    private DataSourceRegistry dataSourceRegistry;

    @Autowired
    private DataProviderRegistry providerRegistry;

    private ResponseEntity<DataResult> getPanelOk(String dashboardId, String panelId) {
        return rest.getForEntity("/api/dashboards/{d}/panels/{p}/data", DataResult.class, dashboardId, panelId);
    }

    private ResponseEntity<ApiError> getPanelError(String dashboardId, String panelId) {
        return rest.getForEntity("/api/dashboards/{d}/panels/{p}/data", ApiError.class, dashboardId, panelId);
    }

    @Test
    void missingTable_returns502WithClearError() {
        ResponseEntity<ApiError> response = getPanelError("fault-injection-lab", "panel-missing-table");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error()).isEqualTo("data_execution_failed");
        assertThat(response.getBody().message()).containsIgnoringCase("legacy_billing_ledger");
    }

    @Test
    void neverPopulatedTable_returnsOkWithZeroRowsNotAnError() {
        ResponseEntity<DataResult> response = getPanelOk("fault-injection-lab", "panel-empty-table");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status().name()).isEqualTo("OK");
        assertThat(response.getBody().rowCount()).isZero();
        assertThat(response.getBody().rows()).isEmpty();
    }

    @Test
    void populatedTableFilteredToZeroRows_returnsOkWithZeroRows() {
        ResponseEntity<DataResult> response = getPanelOk("fault-injection-lab", "panel-empty-filtered");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().rowCount()).isZero();
        // Columns still come back from result-set metadata even with zero rows - the frontend
        // can render an empty-but-correctly-shaped table instead of nothing.
        assertThat(response.getBody().columns()).isNotEmpty();
    }

    @Test
    void schemaDrift_failsAfterColumnRenamedOutOfBand_andDoesNotAffectOtherPanels() {
        ResponseEntity<DataResult> before = getPanelOk("fault-injection-lab", "panel-schema-drift");
        assertThat(before.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(before.getBody().rows().get(0)).containsKey("total_amount");

        // Simulates another microservice migrating the table out from under Panopticon.
        rawDb.execute("ALTER TABLE legacy_billing_summary RENAME COLUMN total_amount TO amount_total");
        try {
            ResponseEntity<ApiError> after = getPanelError("fault-injection-lab", "panel-schema-drift");
            assertThat(after.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
            assertThat(after.getBody().message()).containsIgnoringCase("total_amount");

            // A completely unrelated panel on a different dashboard must be unaffected - one
            // broken data definition/table must never take down the whole app.
            ResponseEntity<DataResult> unrelated = getPanelOk("user-operations", "kpi-active-users");
            assertThat(unrelated.getStatusCode()).isEqualTo(HttpStatus.OK);
        } finally {
            rawDb.execute("ALTER TABLE legacy_billing_summary RENAME COLUMN amount_total TO total_amount");
        }
    }

    @Test
    void maxRowsTruncatesEvenWhenMoreRowsExist() {
        await().atMost(Duration.ofSeconds(10)).until(() -> simulator.eventsEnqueued() >= 6);

        ResponseEntity<DataResult> response = getPanelOk("fault-injection-lab", "panel-maxrows-truncation");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().rowCount()).isLessThanOrEqualTo(5);
        assertThat(response.getBody().rows()).hasSizeLessThanOrEqualTo(5);
        long actualEventCount = rawDb.count("SELECT COUNT(*) FROM event_outbox");
        assertThat(actualEventCount).isGreaterThan(5);
    }

    @Test
    void mismatchedFieldOptions_apiStillReturns200WithRawColumnNames() {
        // Documents current (intentional) behavior: PanelDefinition.options is a loose,
        // provider-agnostic Map<String,Object> and DataEngine/JdbcDataProvider never cross-check it
        // against the query's actual result columns - that mapping only happens in the frontend
        // renderer. A bar panel configured with xField "category"/yField "value" against a query
        // that returns "state"/"n" is a real, silent misconfiguration this suite documents rather
        // than treats as a backend bug.
        ResponseEntity<DataResult> response = getPanelOk("fault-injection-lab", "panel-mismatched-fields");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().rows()).isNotEmpty();
        assertThat(response.getBody().columns()).extracting(c -> c.name()).containsExactlyInAnyOrder("state", "n");
    }

    @Test
    void unknownDataId_throwsUnknownDataException() {
        assertThatThrownBy(() -> dataEngine.execute("this-data-id-does-not-exist"))
                .isInstanceOf(UnknownDataException.class);
    }

    @Test
    void dataDefinitionWithUnsupportedProvider_throwsUnsupportedProviderException() {
        // ConfigValidator rejects an unsupported provider at startup (see RegistryConfig), so this
        // branch of DataEngine can never be reached through a real config file - only reachable via
        // a hand-built DataRegistry, which is exactly what a defensive/second layer of validation is
        // for: proving DataEngine itself still refuses to silently misroute the request.
        DataDefinition bogus = new DataDefinition("bogus-provider-def", "x", "totally-unsupported-provider",
                "prod-like-sqlite", null, null, 0, "SELECT 1", null, null, null, null);
        DataEngine standalone = new DataEngine(new DataRegistry(List.of(bogus)), dataSourceRegistry, providerRegistry, new DataResultCache());

        assertThatThrownBy(() -> standalone.execute("bogus-provider-def"))
                .isInstanceOf(UnsupportedProviderException.class);
    }

    @Test
    void dataDefinitionWithUnknownDatasource_throwsNoSuchDataSourceException() {
        DataDefinition bogus = new DataDefinition("bogus-datasource-def", "x", "jdbc",
                "totally-unknown-datasource", null, null, 0, "SELECT 1", null, null, null, null);
        DataEngine standalone = new DataEngine(new DataRegistry(List.of(bogus)), dataSourceRegistry, providerRegistry, new DataResultCache());

        assertThatThrownBy(() -> standalone.execute("bogus-datasource-def"))
                .isInstanceOf(DataSourceRegistry.NoSuchDataSourceException.class);
    }

    @Test
    void failuresAreNegativelyCached_staleErrorSurvivesUntilTtlExpiresEvenAfterTheRootCauseIsFixed() {
        assertThatThrownBy(() -> dataEngine.execute("fault-missing-table-cached"))
                .isInstanceOf(DataExecutionException.class);

        // Fix the root cause mid-TTL: if the cache is doing its job, the next call still fails
        // with the *cached* error instead of re-running against the now-real table.
        rawDb.execute("CREATE TABLE table_that_appears_mid_test (id INTEGER PRIMARY KEY)");
        try {
            assertThatThrownBy(() -> dataEngine.execute("fault-missing-table-cached"))
                    .isInstanceOf(DataExecutionException.class);

            // After the 3s TTL, the cache expires and the (now genuinely working) query succeeds.
            // ignoreExceptions() is required: dataEngine.execute() throws (not just fails an
            // assertion) for as long as the cached failure is still fresh, and Awaitility does not
            // treat a plain RuntimeException from the probe itself as "not yet satisfied" by default.
            await().atMost(Duration.ofSeconds(6)).pollInterval(Duration.ofMillis(200)).ignoreExceptions().untilAsserted(() -> {
                DataResult result = dataEngine.execute("fault-missing-table-cached");
                assertThat(result.status().name()).isEqualTo("OK");
            });
        } finally {
            rawDb.execute("DROP TABLE table_that_appears_mid_test");
        }
    }
}
