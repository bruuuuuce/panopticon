package com.panopticon.loader;

import com.panopticon.data.DataProviderRegistry;
import com.panopticon.data.jdbc.JdbcDataProvider;
import com.panopticon.data.jdbc.SqlGuard;
import com.panopticon.data.jdbc.SqlGuardException;
import com.panopticon.data.jira.JiraDataProvider;
import com.panopticon.model.DashboardDefinition;
import com.panopticon.model.DataDefinition;
import com.panopticon.model.GridPosition;
import com.panopticon.model.PanelDefinition;
import com.panopticon.model.PanelType;
import com.panopticon.registry.DataSourceRegistry;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Cross-checks parsed dashboards/data definitions: unique ids, panels
 * referencing a real data definition, data definitions referencing a
 * configured datasource and a registered provider, provider-specific
 * required fields (SQL for jdbc, operation for jira). Pure/stateless so it
 * can be reused both at startup (fail fast) and by the
 * {@code /api/config/validate} dry-run endpoint.
 */
public final class ConfigValidator {

    private static final Pattern HEX_COLOR = Pattern.compile("^#([0-9a-fA-F]{3}|[0-9a-fA-F]{6})$");

    private ConfigValidator() {
    }

    public static ValidationResult validate(
            List<DataDefinition> dataDefinitions,
            List<DashboardDefinition> dashboards,
            DataSourceRegistry dataSourceRegistry,
            DataProviderRegistry providerRegistry) {

        List<ValidationError> errors = new ArrayList<>();
        Map<String, DataDefinition> dataById = new HashMap<>();

        for (DataDefinition data : dataDefinitions) {
            if (data.id() == null || data.id().isBlank()) {
                errors.add(new ValidationError("data", "Data definition is missing an 'id'"));
                continue;
            }
            if (dataById.containsKey(data.id())) {
                errors.add(new ValidationError("data:" + data.id(), "Duplicate data id"));
                continue;
            }
            dataById.put(data.id(), data);
            validateDataDefinition(data, dataSourceRegistry, providerRegistry, errors);
        }

        Set<String> dashboardIds = new HashSet<>();
        for (DashboardDefinition dashboard : dashboards) {
            if (dashboard.id() == null || dashboard.id().isBlank()) {
                errors.add(new ValidationError("dashboard", "Dashboard is missing an 'id'"));
                continue;
            }
            if (!dashboardIds.add(dashboard.id())) {
                errors.add(new ValidationError("dashboard:" + dashboard.id(), "Duplicate dashboard id"));
            }
            if (dashboard.panels().isEmpty()) {
                errors.add(new ValidationError("dashboard:" + dashboard.id(), "Dashboard has no panels"));
            }
            if (dashboard.accentColor() != null && !HEX_COLOR.matcher(dashboard.accentColor()).matches()) {
                errors.add(new ValidationError("dashboard:" + dashboard.id(),
                        "'accentColor' must be a hex color like #3987e5, got '%s'".formatted(dashboard.accentColor())));
            }

            Set<String> panelIds = new HashSet<>();
            for (PanelDefinition panel : dashboard.panels()) {
                String panelSource = "dashboard:%s panel:%s".formatted(dashboard.id(), panel.id());
                if (panel.id() == null || panel.id().isBlank()) {
                    errors.add(new ValidationError("dashboard:" + dashboard.id(), "Panel is missing an 'id'"));
                } else if (!panelIds.add(panel.id())) {
                    errors.add(new ValidationError(panelSource, "Duplicate panel id within dashboard"));
                }
                if (panel.dataRef() == null || !dataById.containsKey(panel.dataRef())) {
                    errors.add(new ValidationError(panelSource,
                            "References unknown dataRef '%s'".formatted(panel.dataRef())));
                }
                if (panel.grid() == null) {
                    errors.add(new ValidationError(panelSource, "Panel is missing 'grid' position"));
                } else {
                    validateGridPosition(panelSource, panel.grid(), dashboard.gridColumns(), errors);
                }
                if (panel.type() == null) {
                    errors.add(new ValidationError(panelSource, "Panel is missing 'type'"));
                } else {
                    validateChartMappings(panelSource, panel.type(), panel.options(), errors);
                }
            }
        }

        return ValidationResult.failed(errors, dashboards.size(), dataDefinitions.size());
    }

    private static void validateDataDefinition(
            DataDefinition data, DataSourceRegistry dataSourceRegistry, DataProviderRegistry providerRegistry, List<ValidationError> errors) {

        String source = "data:" + data.id();

        if (data.provider() == null || data.provider().isBlank()) {
            errors.add(new ValidationError(source, "Data definition is missing 'provider'"));
        } else if (!providerRegistry.supports(data.provider())) {
            errors.add(new ValidationError(source,
                    "Unsupported provider type '%s'. Registered providers: %s".formatted(data.provider(), providerRegistry.knownProviderTypes())));
        }

        if (data.datasource() == null || data.datasource().isBlank() || !dataSourceRegistry.contains(data.datasource())) {
            errors.add(new ValidationError(source, "Unknown datasource '%s'".formatted(data.datasource())));
        }

        if (JdbcDataProvider.PROVIDER_TYPE.equals(data.provider())) {
            if (data.sql() == null || data.sql().isBlank()) {
                errors.add(new ValidationError(source, "jdbc data definition is missing 'sql'"));
            } else {
                try {
                    SqlGuard.assertReadOnly(data.sql());
                } catch (SqlGuardException e) {
                    errors.add(new ValidationError(source, e.getMessage()));
                }
            }
        } else if (JiraDataProvider.PROVIDER_TYPE.equals(data.provider())) {
            if (data.operation() == null || data.operation().isBlank()) {
                errors.add(new ValidationError(source, "jira data definition is missing 'operation'"));
            }
        }
    }

    private static void validateGridPosition(String panelSource, GridPosition grid, int gridColumns, List<ValidationError> errors) {
        if (grid.row() < 1) {
            errors.add(new ValidationError(panelSource, "'grid.row' must be >= 1"));
        }
        if (grid.col() < 1) {
            errors.add(new ValidationError(panelSource, "'grid.col' must be >= 1"));
        }
        if (grid.rowSpan() < 1) {
            errors.add(new ValidationError(panelSource, "'grid.rowSpan' must be >= 1"));
        }
        if (grid.colSpan() < 1) {
            errors.add(new ValidationError(panelSource, "'grid.colSpan' must be >= 1"));
        }
        if (grid.col() >= 1 && grid.colSpan() >= 1 && grid.col() + grid.colSpan() - 1 > gridColumns) {
            errors.add(new ValidationError(panelSource,
                    "Panel spans column %d, past the dashboard's %d-column grid".formatted(
                            grid.col() + grid.colSpan() - 1, gridColumns)));
        }
    }

    /** Each chart-shaped panel type needs specific fields in `options` to know what to plot. */
    private static void validateChartMappings(String panelSource, PanelType type, Map<String, Object> options, List<ValidationError> errors) {
        switch (type) {
            case STAT -> requireOptions(panelSource, type, options, errors, "valueField");
            case BAR, LINE -> requireOptions(panelSource, type, options, errors, "xField", "yField");
            case DONUT -> requireOptions(panelSource, type, options, errors, "labelField", "valueField");
            case TABLE -> { /* columns is optional; omitting it means "show every column" */ }
        }
    }

    private static void requireOptions(
            String panelSource, PanelType type, Map<String, Object> options, List<ValidationError> errors, String... keys) {
        for (String key : keys) {
            Object value = options.get(key);
            if (value == null || (value instanceof String s && s.isBlank())) {
                errors.add(new ValidationError(panelSource,
                        "Panel type '%s' requires options.%s".formatted(type.toJson(), key)));
            }
        }
    }
}
