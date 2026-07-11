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

import java.nio.file.Path;
import java.util.List;

/**
 * Loads dashboards/data definitions from disk once at startup and fails
 * fast if the configuration is invalid, so a broken config/ directory never
 * results in a half-working app. For editing feedback without a restart,
 * see {@code POST /api/config/validate}, which re-runs the same
 * loader/validator as a dry run against the live registries.
 */
@Configuration
public class RegistryConfig {

    @Bean
    public DataRegistry dataRegistry(ConfigLoader loader, ConfigPathsProperties paths) {
        List<DataDefinition> definitions = loader.loadDataDefinitions(Path.of(paths.dataPath()));
        return new DataRegistry(definitions);
    }

    @Bean
    public DashboardRegistry dashboardRegistry(
            ConfigLoader loader,
            ConfigPathsProperties paths,
            DataRegistry dataRegistry,
            DataSourceRegistry dataSourceRegistry,
            DataProviderRegistry providerRegistry) {

        List<DashboardDefinition> dashboards = loader.loadDashboards(Path.of(paths.dashboardsPath()));
        ValidationResult result = ConfigValidator.validate(
                dataRegistry.all().stream().toList(), dashboards, dataSourceRegistry, providerRegistry);
        if (!result.valid()) {
            throw new ConfigLoadException("Invalid dashboard/data configuration: " + result.errors());
        }
        return new DashboardRegistry(dashboards);
    }
}
