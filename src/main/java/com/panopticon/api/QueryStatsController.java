package com.panopticon.api;

import com.panopticon.data.DataExecutionContext;
import com.panopticon.data.DataProvider;
import com.panopticon.data.DataProviderRegistry;
import com.panopticon.data.plan.ExplainCapable;
import com.panopticon.data.plan.QueryPlanResult;
import com.panopticon.data.stats.QueryExecutionStatsTracker;
import com.panopticon.data.stats.QueryStatsSnapshot;
import com.panopticon.model.DataDefinition;
import com.panopticon.model.DataSourceDefinition;
import com.panopticon.registry.DataRegistry;
import com.panopticon.registry.DataSourceRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.List;

/**
 * Read-only "ops" view over every loaded data definition: execution stats
 * (see {@link QueryExecutionStatsTracker}) for every configured query, plus
 * a best-effort execution plan for jdbc-backed ones, fetched on demand.
 * Never triggers a real data execution itself — {@code /plan} runs an
 * explicit {@code EXPLAIN}, not the query.
 */
@RestController
@RequestMapping("/api/query-stats")
public class QueryStatsController {

    private final DataRegistry dataRegistry;
    private final DataSourceRegistry dataSourceRegistry;
    private final DataProviderRegistry providerRegistry;
    private final QueryExecutionStatsTracker statsTracker;

    public QueryStatsController(
            DataRegistry dataRegistry,
            DataSourceRegistry dataSourceRegistry,
            DataProviderRegistry providerRegistry,
            QueryExecutionStatsTracker statsTracker) {
        this.dataRegistry = dataRegistry;
        this.dataSourceRegistry = dataSourceRegistry;
        this.providerRegistry = providerRegistry;
        this.statsTracker = statsTracker;
    }

    @GetMapping
    public List<QueryStatsSnapshot> stats() {
        return dataRegistry.all().stream()
                .map(d -> statsTracker.snapshot(d.id(), d.name(), d.provider(), d.datasource(), datasourceDisplayName(d.datasource())))
                .sorted(Comparator.comparing(QueryStatsSnapshot::dataId))
                .toList();
    }

    /**
     * A data definition can reference a datasource name that isn't actually
     * configured (config validation catches this before startup for real
     * config files, but a hand-built definition in a test could still reach
     * here) — fall back to the raw key rather than fail the whole listing
     * over one bad reference the rest of this endpoint doesn't otherwise care about.
     */
    private String datasourceDisplayName(String datasourceName) {
        return dataSourceRegistry.find(datasourceName)
                .map(DataSourceDefinition::displayName)
                .orElse(datasourceName);
    }

    @GetMapping("/{dataId}/plan")
    public QueryPlanResult plan(@PathVariable String dataId) {
        DataDefinition definition = dataRegistry.find(dataId)
                .orElseThrow(() -> new NotFoundException("Data definition '%s' not found".formatted(dataId)));
        DataSourceDefinition datasource = dataSourceRegistry.find(definition.datasource())
                .orElseThrow(() -> new DataSourceRegistry.NoSuchDataSourceException(definition.datasource(), dataSourceRegistry.names()));
        DataProvider provider = providerRegistry.resolve(definition.provider());

        if (provider instanceof ExplainCapable explainCapable) {
            return explainCapable.explain(new DataExecutionContext(definition, datasource));
        }
        return QueryPlanResult.unsupported(null,
                "Provider '%s' does not support execution plans".formatted(definition.provider()));
    }
}
