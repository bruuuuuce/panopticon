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
import com.panopticon.model.ThresholdDirection;
import com.panopticon.model.ThresholdRule;
import com.panopticon.registry.DataSourceRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ThresholdRule is a fixed alerting bound evaluated client-side (see
 * thresholds.js) - the backend's only job is to reject a nonsensical config
 * before it ever reaches the frontend (missing field, no bound set at all,
 * or a warning/critical pair that can never both fire in the configured
 * direction). Same stub setup as {@link DashboardAccentColorValidationTest}:
 * ConfigValidator is a pure static function, no Spring context needed.
 */
class ThresholdRuleValidationTest {

    @Test
    void acceptsAWarningOnlyRule() {
        assertThat(validate(rule("n", 10.0, null, ThresholdDirection.ABOVE)).errors()).isEmpty();
    }

    @Test
    void acceptsACriticalOnlyRule() {
        assertThat(validate(rule("n", null, 10.0, ThresholdDirection.ABOVE)).errors()).isEmpty();
    }

    @Test
    void acceptsAnOrderedAboveRule() {
        assertThat(validate(rule("n", 10.0, 20.0, ThresholdDirection.ABOVE)).errors()).isEmpty();
    }

    @Test
    void acceptsAnOrderedBelowRule() {
        assertThat(validate(rule("n", 10.0, 5.0, ThresholdDirection.BELOW)).errors()).isEmpty();
    }

    @Test
    void acceptsEqualWarningAndCritical() {
        // warning == critical is degenerate but not contradictory in either direction.
        assertThat(validate(rule("n", 10.0, 10.0, ThresholdDirection.ABOVE)).errors()).isEmpty();
    }

    @Test
    void defaultsToAboveWhenDirectionIsOmitted() {
        ThresholdRule rule = new ThresholdRule("n", null, 10.0, 20.0, null);
        assertThat(rule.direction()).isEqualTo(ThresholdDirection.ABOVE);
        assertThat(validate(rule).errors()).isEmpty();
    }

    @Test
    void rejectsAMissingField() {
        ValidationResult result = validate(rule(null, 10.0, 20.0, ThresholdDirection.ABOVE));
        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.message().contains("field"));
    }

    @Test
    void rejectsABlankField() {
        ValidationResult result = validate(rule("   ", 10.0, 20.0, ThresholdDirection.ABOVE));
        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.message().contains("field"));
    }

    @Test
    void rejectsARuleWithNeitherWarningNorCritical() {
        ValidationResult result = validate(rule("n", null, null, ThresholdDirection.ABOVE));
        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.message().contains("warning") && e.message().contains("critical"));
    }

    @Test
    void rejectsAnInvertedAboveRule() {
        ValidationResult result = validate(rule("n", 20.0, 10.0, ThresholdDirection.ABOVE));
        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.message().contains("out of order"));
    }

    @Test
    void rejectsAnInvertedBelowRule() {
        ValidationResult result = validate(rule("n", 5.0, 10.0, ThresholdDirection.BELOW));
        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.message().contains("out of order"));
    }

    private ThresholdRule rule(String field, Double warning, Double critical, ThresholdDirection direction) {
        return new ThresholdRule(field, "Label", warning, critical, direction);
    }

    private ValidationResult validate(ThresholdRule rule) {
        PanelDefinition panel = new PanelDefinition(
                "p1", "Panel", PanelType.STAT, "d1",
                new GridPosition(1, 1, 1, 3), new RefreshPolicy(30, true),
                Map.of("valueField", "n"), List.of(rule));
        DashboardDefinition dashboard = new DashboardDefinition("dash1", "Dash", "desc", 12, List.of(panel), null, null);

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
