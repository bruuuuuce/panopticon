package com.panopticon.api;

import com.panopticon.api.dto.DashboardSummary;
import com.panopticon.data.DataEngine;
import com.panopticon.data.DataExecutionException;
import com.panopticon.data.UnknownDataException;
import com.panopticon.data.UnsupportedProviderException;
import com.panopticon.data.jdbc.SqlGuardException;
import com.panopticon.data.stats.AdaptiveThresholdTracker;
import com.panopticon.model.AdaptiveBaseline;
import com.panopticon.model.DashboardDefinition;
import com.panopticon.model.DataResult;
import com.panopticon.model.PanelDefinition;
import com.panopticon.model.PanelType;
import com.panopticon.registry.DashboardRegistry;
import com.panopticon.registry.DataSourceRegistry;
import com.panopticon.runtime.PanelRuntimeTracker;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/dashboards")
public class DashboardController {

    private final DashboardRegistry dashboardRegistry;
    private final DataEngine dataEngine;
    private final PanelRuntimeTracker runtimeTracker;
    private final AdaptiveThresholdTracker adaptiveThresholdTracker;

    public DashboardController(
            DashboardRegistry dashboardRegistry, DataEngine dataEngine, PanelRuntimeTracker runtimeTracker,
            AdaptiveThresholdTracker adaptiveThresholdTracker) {
        this.dashboardRegistry = dashboardRegistry;
        this.dataEngine = dataEngine;
        this.runtimeTracker = runtimeTracker;
        this.adaptiveThresholdTracker = adaptiveThresholdTracker;
    }

    @GetMapping
    public List<DashboardSummary> listDashboards() {
        return dashboardRegistry.all().stream().map(DashboardSummary::from).toList();
    }

    @GetMapping("/{dashboardId}")
    public DashboardDefinition getDashboard(@PathVariable String dashboardId) {
        return findDashboard(dashboardId);
    }

    @GetMapping("/{dashboardId}/panels/{panelId}/data")
    public DataResult getPanelData(@PathVariable String dashboardId, @PathVariable String panelId) {
        DashboardDefinition dashboard = findDashboard(dashboardId);
        PanelDefinition panel = dashboard.panels().stream()
                .filter(p -> p.id().equals(panelId))
                .findFirst()
                .orElseThrow(() -> new NotFoundException(
                        "Panel '%s' not found in dashboard '%s'".formatted(panelId, dashboardId)));

        try {
            DataResult result = dataEngine.execute(panel.dataRef());
            runtimeTracker.recordSuccess(dashboardId, panelId, panel.dataRef(), result.executionTimeMs(), result.rowCount());
            result = attachAdaptiveBaseline(panel, result);
            return result;
        } catch (DataExecutionException | SqlGuardException | UnknownDataException | UnsupportedProviderException
                 | DataSourceRegistry.NoSuchDataSourceException e) {
            runtimeTracker.recordFailure(dashboardId, panelId, panel.dataRef(), e.getMessage());
            throw e;
        }
    }

    /**
     * Only 'stat' panels have a single primary value worth tracking history
     * for (see AdaptiveThresholdTracker); this is the one place that has both
     * the PanelDefinition (to know options.valueField) and the freshly
     * executed DataResult together, same reasoning as why datasourceName is
     * attached in DataEngine rather than by the provider.
     */
    private DataResult attachAdaptiveBaseline(PanelDefinition panel, DataResult result) {
        if (panel.type() != PanelType.STAT) {
            return result;
        }
        String valueField = stringOption(panel.options(), "valueField");
        if (valueField == null) {
            return result;
        }
        Double value = numericValue(result, valueField);
        adaptiveThresholdTracker.record(panel.dataRef(), valueField, value);
        AdaptiveBaseline baseline = adaptiveThresholdTracker.baseline(panel.dataRef(), valueField);
        return result.withAdaptiveBaseline(baseline);
    }

    private String stringOption(Map<String, Object> options, String key) {
        Object value = options.get(key);
        return value instanceof String s && !s.isBlank() ? s : null;
    }

    private Double numericValue(DataResult result, String field) {
        if (result.rows().isEmpty()) {
            return null;
        }
        Object raw = result.rows().get(0).get(field);
        if (raw instanceof Number n) {
            return n.doubleValue();
        }
        return null;
    }

    private DashboardDefinition findDashboard(String dashboardId) {
        return dashboardRegistry.find(dashboardId)
                .orElseThrow(() -> new NotFoundException("Dashboard '%s' not found".formatted(dashboardId)));
    }
}
