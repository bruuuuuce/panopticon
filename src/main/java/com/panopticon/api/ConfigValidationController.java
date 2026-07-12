package com.panopticon.api;

import com.panopticon.config.DashboardConfigLocations;
import com.panopticon.data.DataProviderRegistry;
import com.panopticon.loader.ConfigLoadException;
import com.panopticon.loader.ConfigLoader;
import com.panopticon.loader.ConfigValidator;
import com.panopticon.loader.ValidationError;
import com.panopticon.loader.ValidationResult;
import com.panopticon.model.DashboardDefinition;
import com.panopticon.model.DataDefinition;
import com.panopticon.registry.DataSourceRegistry;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Dry-run validation of the dashboard/data config as it currently sits on
 * disk, at whatever locations are effectively in play (see
 * {@link DashboardConfigLocations} - the default config/ directories, or
 * whatever {@code --dashboards}/{@code --data} were started with).
 * Deliberately does not swap the live registries: this endpoint is meant for
 * authoring feedback ("will this config load cleanly?"), not for hot
 * reload, which is out of scope for the MVP.
 */
@RestController
@RequestMapping("/api/config")
public class ConfigValidationController {

    private final ConfigLoader configLoader;
    private final DashboardConfigLocations locations;
    private final DataSourceRegistry dataSourceRegistry;
    private final DataProviderRegistry providerRegistry;

    public ConfigValidationController(
            ConfigLoader configLoader,
            DashboardConfigLocations locations,
            DataSourceRegistry dataSourceRegistry,
            DataProviderRegistry providerRegistry) {
        this.configLoader = configLoader;
        this.locations = locations;
        this.dataSourceRegistry = dataSourceRegistry;
        this.providerRegistry = providerRegistry;
    }

    @PostMapping("/validate")
    public ValidationResult validate() {
        try {
            List<DataDefinition> dataDefinitions = configLoader.loadDataDefinitions(locations.data());
            List<DashboardDefinition> dashboards = configLoader.loadDashboards(locations.dashboards());
            return ConfigValidator.validate(dataDefinitions, dashboards, dataSourceRegistry, providerRegistry);
        } catch (ConfigLoadException e) {
            return ValidationResult.failed(List.of(new ValidationError("config", e.getMessage())), 0, 0);
        }
    }
}
