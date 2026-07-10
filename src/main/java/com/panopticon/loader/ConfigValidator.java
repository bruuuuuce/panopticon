package com.panopticon.loader;

import com.panopticon.model.DashboardDefinition;
import com.panopticon.model.GridPosition;
import com.panopticon.model.PanelDefinition;
import com.panopticon.model.PanelType;
import com.panopticon.model.QueryDefinition;
import com.panopticon.query.SqlGuard;
import com.panopticon.query.SqlGuardException;
import com.panopticon.registry.DatasourceRegistry;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Cross-checks parsed dashboards/queries: unique ids, panels referencing a
 * real query, queries referencing a configured datasource. Pure/stateless
 * so it can be reused both at startup (fail fast) and by the
 * {@code /api/config/validate} dry-run endpoint.
 */
public final class ConfigValidator {

    private ConfigValidator() {
    }

    public static ValidationResult validate(
            List<QueryDefinition> queries, List<DashboardDefinition> dashboards, DatasourceRegistry datasourceRegistry) {

        List<ValidationError> errors = new ArrayList<>();
        Map<String, QueryDefinition> queryById = new HashMap<>();

        for (QueryDefinition query : queries) {
            if (query.id() == null || query.id().isBlank()) {
                errors.add(new ValidationError("query", "Query is missing an 'id'"));
                continue;
            }
            if (queryById.containsKey(query.id())) {
                errors.add(new ValidationError("query:" + query.id(), "Duplicate query id"));
                continue;
            }
            queryById.put(query.id(), query);
            if (query.sql() == null || query.sql().isBlank()) {
                errors.add(new ValidationError("query:" + query.id(), "Query is missing 'sql'"));
            } else {
                try {
                    SqlGuard.assertReadOnly(query.sql());
                } catch (SqlGuardException e) {
                    errors.add(new ValidationError("query:" + query.id(), e.getMessage()));
                }
            }
            if (query.datasource() == null || !datasourceRegistry.contains(query.datasource())) {
                errors.add(new ValidationError("query:" + query.id(),
                        "Unknown datasource '%s'".formatted(query.datasource())));
            }
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

            Set<String> panelIds = new HashSet<>();
            for (PanelDefinition panel : dashboard.panels()) {
                String panelSource = "dashboard:%s panel:%s".formatted(dashboard.id(), panel.id());
                if (panel.id() == null || panel.id().isBlank()) {
                    errors.add(new ValidationError("dashboard:" + dashboard.id(), "Panel is missing an 'id'"));
                } else if (!panelIds.add(panel.id())) {
                    errors.add(new ValidationError(panelSource, "Duplicate panel id within dashboard"));
                }
                if (panel.queryRef() == null || !queryById.containsKey(panel.queryRef())) {
                    errors.add(new ValidationError(panelSource,
                            "References unknown queryRef '%s'".formatted(panel.queryRef())));
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

        return ValidationResult.failed(errors, dashboards.size(), queries.size());
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
