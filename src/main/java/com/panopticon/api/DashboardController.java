package com.panopticon.api;

import com.panopticon.api.dto.DashboardSummary;
import com.panopticon.model.DashboardDefinition;
import com.panopticon.model.PanelDefinition;
import com.panopticon.model.QueryResult;
import com.panopticon.query.QueryEngine;
import com.panopticon.query.QueryExecutionException;
import com.panopticon.query.SqlGuardException;
import com.panopticon.query.UnknownQueryException;
import com.panopticon.registry.DashboardRegistry;
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
    private final QueryEngine queryEngine;
    private final PanelRuntimeTracker runtimeTracker;

    public DashboardController(DashboardRegistry dashboardRegistry, QueryEngine queryEngine, PanelRuntimeTracker runtimeTracker) {
        this.dashboardRegistry = dashboardRegistry;
        this.queryEngine = queryEngine;
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
    public QueryResult getPanelData(@PathVariable String dashboardId, @PathVariable String panelId) {
        DashboardDefinition dashboard = findDashboard(dashboardId);
        PanelDefinition panel = dashboard.panels().stream()
                .filter(p -> p.id().equals(panelId))
                .findFirst()
                .orElseThrow(() -> new NotFoundException(
                        "Panel '%s' not found in dashboard '%s'".formatted(panelId, dashboardId)));

        try {
            QueryResult result = queryEngine.execute(panel.queryRef());
            runtimeTracker.recordSuccess(dashboardId, panelId, panel.queryRef(), result.executionTimeMs(), result.rowCount());
            return result;
        } catch (QueryExecutionException | SqlGuardException | UnknownQueryException e) {
            runtimeTracker.recordFailure(dashboardId, panelId, panel.queryRef(), e.getMessage());
            throw e;
        }
    }

    private DashboardDefinition findDashboard(String dashboardId) {
        return dashboardRegistry.find(dashboardId)
                .orElseThrow(() -> new NotFoundException("Dashboard '%s' not found".formatted(dashboardId)));
    }
}
