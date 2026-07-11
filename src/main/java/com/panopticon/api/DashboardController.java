package com.panopticon.api;

import com.panopticon.api.dto.DashboardSummary;
import com.panopticon.data.DataEngine;
import com.panopticon.data.DataExecutionException;
import com.panopticon.data.UnknownDataException;
import com.panopticon.data.UnsupportedProviderException;
import com.panopticon.data.jdbc.SqlGuardException;
import com.panopticon.model.DashboardDefinition;
import com.panopticon.model.DataResult;
import com.panopticon.model.PanelDefinition;
import com.panopticon.registry.DashboardRegistry;
import com.panopticon.registry.DataSourceRegistry;
import com.panopticon.runtime.PanelRuntimeTracker;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/dashboards")
public class DashboardController {

    private final DashboardRegistry dashboardRegistry;
    private final DataEngine dataEngine;
    private final PanelRuntimeTracker runtimeTracker;

    public DashboardController(DashboardRegistry dashboardRegistry, DataEngine dataEngine, PanelRuntimeTracker runtimeTracker) {
        this.dashboardRegistry = dashboardRegistry;
        this.dataEngine = dataEngine;
        this.runtimeTracker = runtimeTracker;
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
            return result;
        } catch (DataExecutionException | SqlGuardException | UnknownDataException | UnsupportedProviderException
                 | DataSourceRegistry.NoSuchDataSourceException e) {
            runtimeTracker.recordFailure(dashboardId, panelId, panel.dataRef(), e.getMessage());
            throw e;
        }
    }

    private DashboardDefinition findDashboard(String dashboardId) {
        return dashboardRegistry.find(dashboardId)
                .orElseThrow(() -> new NotFoundException("Dashboard '%s' not found".formatted(dashboardId)));
    }
}
