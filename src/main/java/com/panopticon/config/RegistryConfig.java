package com.panopticon.config;

import com.panopticon.loader.ConfigLoadException;
import com.panopticon.loader.ConfigLoader;
import com.panopticon.loader.ConfigValidator;
import com.panopticon.loader.ValidationResult;
import com.panopticon.model.DashboardDefinition;
import com.panopticon.model.QueryDefinition;
import com.panopticon.registry.DashboardRegistry;
import com.panopticon.registry.DatasourceRegistry;
import com.panopticon.registry.QueryRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.util.List;

/**
 * Loads dashboards/queries from disk once at startup and fails fast if the
 * configuration is invalid, so a broken config/ directory never results in
 * a half-working app. For editing feedback without a restart, see
 * {@code POST /api/config/validate}, which re-runs the same loader/validator
 * as a dry run against the live registries.
 */
@Configuration
public class RegistryConfig {

    @Bean
    public QueryRegistry queryRegistry(ConfigLoader loader, ConfigPathsProperties paths) {
        List<QueryDefinition> queries = loader.loadQueries(Path.of(paths.queriesPath()));
        return new QueryRegistry(queries);
    }

    @Bean
    public DashboardRegistry dashboardRegistry(
            ConfigLoader loader,
            ConfigPathsProperties paths,
            QueryRegistry queryRegistry,
            DatasourceRegistry datasourceRegistry) {

        List<DashboardDefinition> dashboards = loader.loadDashboards(Path.of(paths.dashboardsPath()));
        ValidationResult result = ConfigValidator.validate(queryRegistry.all().stream().toList(), dashboards, datasourceRegistry);
        if (!result.valid()) {
            throw new ConfigLoadException("Invalid dashboard/query configuration: " + result.errors());
        }
        return new DashboardRegistry(dashboards);
    }
}
