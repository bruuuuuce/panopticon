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
import com.panopticon.model.RotationPolicy;
import com.panopticon.registry.DataSourceRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RefreshPolicy/RotationPolicy are plain records with no bounds of their own,
 * so ConfigValidator is where a nonsensical interval (zero or negative while
 * enabled) must be caught - otherwise the frontend would silently skip
 * refreshes (interval <= 0 disables the timer in dashboard.js) or monitor
 * mode would rotate on a garbage duration. Same stub setup as
 * {@link DashboardAccentColorValidationTest}: no Spring context needed.
 */
class ConfigValidatorPolicyValidationTest {

    @ParameterizedTest
    @ValueSource(ints = {0, -1, -30})
    void rejectsNonPositiveRefreshIntervalWhenEnabled(int intervalSeconds) {
        ValidationResult result = validate(dashboard(new RefreshPolicy(intervalSeconds, true), null));
        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.message().contains("refresh.intervalSeconds"));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, -30})
    void rejectsNonPositiveRotationDurationWhenEnabled(int durationSeconds) {
        ValidationResult result = validate(dashboard(new RefreshPolicy(30, true), new RotationPolicy(durationSeconds, true)));
        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.message().contains("rotation.durationSeconds"));
    }

    @Test
    void allowsNonPositiveIntervalsWhenDisabled() {
        // A disabled policy's interval is never used, so any value is acceptable.
        ValidationResult result = validate(dashboard(new RefreshPolicy(0, false), new RotationPolicy(0, false)));
        assertThat(result.errors()).isEmpty();
    }

    @Test
    void allowsPositiveIntervals() {
        ValidationResult result = validate(dashboard(new RefreshPolicy(30, true), new RotationPolicy(20, true)));
        assertThat(result.errors()).isEmpty();
    }

    private DashboardDefinition dashboard(RefreshPolicy refresh, RotationPolicy rotation) {
        PanelDefinition panel = new PanelDefinition(
                "p1", "Panel", PanelType.TABLE, "d1",
                new GridPosition(1, 1, 1, 12), refresh, Map.of(), List.of());
        return new DashboardDefinition("dash1", "Dash", "desc", 12, List.of(panel), rotation, null);
    }

    private ValidationResult validate(DashboardDefinition dashboard) {
        DataDefinition data = new DataDefinition(
                "d1", "Data", JdbcDataProvider.PROVIDER_TYPE, "ds1", null, null, null,
                "SELECT 1", null, null, null, null);
        DataSourceRegistry datasources = new DataSourceRegistry(Map.of("ds1",
                new com.panopticon.model.DataSourceDefinition("ds1", null, "jdbc", "jdbc:h2:mem:x", "sa", "", "org.h2.Driver",
                        "h2", true, 1, null, null, null, null, null)));
        DataProviderRegistry providers = new DataProviderRegistry(List.of(new StubJdbcProvider()));
        return ConfigValidator.validate(List.of(data), List.of(dashboard), datasources, providers);
    }

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
