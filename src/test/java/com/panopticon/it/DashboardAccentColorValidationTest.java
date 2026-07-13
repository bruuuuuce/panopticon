package com.panopticon.it;

import com.panopticon.data.DataProviderRegistry;
import com.panopticon.data.jdbc.JdbcDataProvider;
import com.panopticon.loader.ConfigValidator;
import com.panopticon.loader.ValidationResult;
import com.panopticon.model.DashboardDefinition;
import com.panopticon.model.DataDefinition;
import com.panopticon.model.GridPosition;
import com.panopticon.model.PanelDefinition;
import com.panopticon.model.PanelType;
import com.panopticon.model.RefreshPolicy;
import com.panopticon.registry.DataSourceRegistry;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DashboardDefinition.accentColor is optional and purely cosmetic (a
 * bottom-to-top page-background wash the frontend applies - see theme.css/
 * dashboard.js), but ConfigValidator still rejects a malformed value at
 * config-load time rather than letting a typo silently no-op in the browser.
 * No Spring context needed: ConfigValidator is a pure static function over
 * hand-built definitions and a couple of trivial registry stand-ins.
 */
class DashboardAccentColorValidationTest {

    @ParameterizedTest
    @ValueSource(strings = {"#3987e5", "#fff", "#FFFFFF", "#000000", "#abc"})
    void acceptsValidHexColors(String accentColor) {
        ValidationResult result = validate(dashboardWithAccent(accentColor));
        assertThat(result.errors()).as("errors for '%s'", accentColor).isEmpty();
    }

    @Test
    void acceptsNoAccentColorAtAll() {
        ValidationResult result = validate(dashboardWithAccent(null));
        assertThat(result.errors()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"blue", "3987e5", "#39g7e5", "#39", "rgb(57,135,229)", "#3987e5ff"})
    void rejectsMalformedAccentColors(String accentColor) {
        ValidationResult result = validate(dashboardWithAccent(accentColor));
        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.message().contains("accentColor"));
    }

    private DashboardDefinition dashboardWithAccent(String accentColor) {
        PanelDefinition panel = new PanelDefinition(
                "p1", "Panel", PanelType.TABLE, "d1",
                new GridPosition(1, 1, 1, 12), new RefreshPolicy(30, true), Map.of(), List.of());
        return new DashboardDefinition("dash1", "Dash", "desc", 12, List.of(panel), null, accentColor);
    }

    private ValidationResult validate(DashboardDefinition dashboard) {
        DataDefinition data = new DataDefinition(
                "d1", "Data", JdbcDataProvider.PROVIDER_TYPE, "ds1", null, null, null,
                "SELECT 1", null, null, null, null);
        // The datasource/provider referenced by the data definition don't need to actually exist
        // for this test - only accentColor validation is under test here - so a real check against
        // an empty registry would ALSO fail; use a registry that actually contains 'ds1'/'jdbc'.
        DataSourceRegistry datasources = new DataSourceRegistry(Map.of("ds1",
                new com.panopticon.model.DataSourceDefinition("ds1", null, "jdbc", "jdbc:h2:mem:x", "sa", "", "org.h2.Driver",
                        "h2", true, 1, null, null, null, null, null)));
        DataProviderRegistry providers = new DataProviderRegistry(List.of(new StubJdbcProvider()));
        return ConfigValidator.validate(List.of(data), List.of(dashboard), datasources, providers);
    }

    /** Just enough of DataProvider to satisfy providerRegistry.supports("jdbc") during validation. */
    private static final class StubJdbcProvider implements com.panopticon.data.DataProvider {
        @Override
        public String providerType() {
            return JdbcDataProvider.PROVIDER_TYPE;
        }


        @Override
        public com.panopticon.model.DataResult execute(com.panopticon.data.DataExecutionContext context) {
            throw new UnsupportedOperationException("not exercised by this test");
        }
    }
}
