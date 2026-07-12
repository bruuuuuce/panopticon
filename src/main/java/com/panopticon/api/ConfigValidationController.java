package com.panopticon.api;

import com.panopticon.config.DashboardConfigLocations;
import com.panopticon.data.DataProviderRegistry;
import com.panopticon.data.DataResultCache;
import com.panopticon.loader.ConfigLoadException;
import com.panopticon.loader.ConfigLoader;
import com.panopticon.loader.ConfigValidator;
import com.panopticon.loader.ValidationError;
import com.panopticon.loader.ValidationResult;
import com.panopticon.model.DashboardDefinition;
import com.panopticon.model.DataDefinition;
import com.panopticon.registry.DashboardRegistry;
import com.panopticon.registry.DataRegistry;
import com.panopticon.registry.DataSourceRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Authoring workflow endpoints over the dashboard/data config as it
 * currently sits on disk, at whatever locations are effectively in play
 * (see {@link DashboardConfigLocations} - the default config/ directories,
 * or whatever {@code --dashboards}/{@code --data} were started with):
 *
 * <ul>
 *   <li>{@code POST /api/config/validate} - dry run: "would this config
 *       load cleanly?", live registries untouched.</li>
 *   <li>{@code POST /api/config/reload} - loads and validates the same way
 *       and, only if fully valid, atomically swaps the live registries (and
 *       clears the result cache, since definitions may have changed under
 *       the same ids). An invalid config is a 400 with the errors and
 *       changes nothing - the running config can never be broken by a bad
 *       reload.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/config")
public class ConfigValidationController {

    private static final Logger log = LoggerFactory.getLogger(ConfigValidationController.class);

    private final ConfigLoader configLoader;
    private final DashboardConfigLocations locations;
    private final DataSourceRegistry dataSourceRegistry;
    private final DataProviderRegistry providerRegistry;
    private final DataRegistry dataRegistry;
    private final DashboardRegistry dashboardRegistry;
    private final DataResultCache resultCache;

    public ConfigValidationController(
            ConfigLoader configLoader,
            DashboardConfigLocations locations,
            DataSourceRegistry dataSourceRegistry,
            DataProviderRegistry providerRegistry,
            DataRegistry dataRegistry,
            DashboardRegistry dashboardRegistry,
            DataResultCache resultCache) {
        this.configLoader = configLoader;
        this.locations = locations;
        this.dataSourceRegistry = dataSourceRegistry;
        this.providerRegistry = providerRegistry;
        this.dataRegistry = dataRegistry;
        this.dashboardRegistry = dashboardRegistry;
        this.resultCache = resultCache;
    }

    @PostMapping("/validate")
    public ValidationResult validate() {
        return loadAndValidate().result();
    }

    @PostMapping("/reload")
    public ResponseEntity<ValidationResult> reload() {
        Loaded loaded = loadAndValidate();
        if (!loaded.result().valid()) {
            log.warn("Config reload rejected: {} validation error(s)", loaded.result().errors().size());
            return ResponseEntity.badRequest().body(loaded.result());
        }
        dataRegistry.replace(loaded.dataDefinitions());
        dashboardRegistry.replace(loaded.dashboards());
        resultCache.clear();
        log.info("Config reloaded: {} dashboards, {} data definitions",
                loaded.dashboards().size(), loaded.dataDefinitions().size());
        return ResponseEntity.ok(loaded.result());
    }

    private record Loaded(ValidationResult result, List<DataDefinition> dataDefinitions, List<DashboardDefinition> dashboards) {
    }

    private Loaded loadAndValidate() {
        try {
            List<DataDefinition> dataDefinitions = configLoader.loadDataDefinitions(locations.data());
            List<DashboardDefinition> dashboards = configLoader.loadDashboards(locations.dashboards());
            ValidationResult result = ConfigValidator.validate(dataDefinitions, dashboards, dataSourceRegistry, providerRegistry);
            return new Loaded(result, dataDefinitions, dashboards);
        } catch (ConfigLoadException e) {
            ValidationResult failed = ValidationResult.failed(List.of(new ValidationError("config", e.getMessage())), 0, 0);
            return new Loaded(failed, List.of(), List.of());
        }
    }
}
