package com.panopticon.config;

import com.panopticon.data.DataProviderRegistry;
import com.panopticon.loader.ConfigLoadException;
import com.panopticon.loader.ConfigLoader;
import com.panopticon.loader.ConfigValidator;
import com.panopticon.loader.ValidationResult;
import com.panopticon.model.DashboardDefinition;
import com.panopticon.model.DataDefinition;
import com.panopticon.registry.DashboardRegistry;
import com.panopticon.registry.DataRegistry;
import com.panopticon.registry.DataSourceRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Loads dashboards/data definitions from disk at startup and fails
 * fast if the configuration is invalid, so a broken config/ directory never
 * results in a half-working app. Without a restart: {@code POST
 * /api/config/validate} re-runs the same loader/validator as a dry run, and
 * {@code POST /api/config/reload} swaps the registries' snapshots if (and
 * only if) the on-disk config validates cleanly.
 */
@Configuration
public class RegistryConfig {

    @Bean
    public DataRegistry dataRegistry(ConfigLoader loader, DashboardConfigLocations locations) {
        List<DataDefinition> definitions = loader.loadDataDefinitions(locations.data());
        return new DataRegistry(definitions);
    }

    @Bean
    public DashboardRegistry dashboardRegistry(
            ConfigLoader loader,
            DashboardConfigLocations locations,
            DataRegistry dataRegistry,
            DataSourceRegistry dataSourceRegistry,
            DataProviderRegistry providerRegistry) {

        List<DashboardDefinition> dashboards = loader.loadDashboards(locations.dashboards());
        ValidationResult result = ConfigValidator.validate(
                dataRegistry.all().stream().toList(), dashboards, dataSourceRegistry, providerRegistry);
        if (!result.valid()) {
            throw new ConfigLoadException("Invalid dashboard/data configuration: " + result.errors());
        }
        return new DashboardRegistry(dashboards);
    }
}
