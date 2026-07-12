package com.panopticon.config;

import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Resolves the effective set of dashboard/data config locations: normally
 * just {@link ConfigPathsProperties}'s single directory each, but overridable
 * at startup with {@code --dashboards=<loc1>,<loc2>,...} and/or
 * {@code --data=<loc1>,<loc2>,...}, where each {@code <locN>} is either a
 * single {@code .json} file or a directory of them (see {@link com.panopticon.loader.ConfigLoader}).
 * This is what lets a real deployment mix a shared dashboards directory with
 * one or two extra one-off files, and lets a test point straight at its own
 * fixture files/directories without needing a whole separate config tree.
 *
 * <p>Bound via the plain Spring {@link Binder} (not a {@code @ConfigurationProperties}
 * class) because the flags are deliberately root-level ({@code --dashboards},
 * not {@code --panopticon.dashboards}) for a short, memorable command line -
 * but this also means they work equally as YAML ({@code dashboards: [...]})
 * or an env var ({@code DASHBOARDS=...}), not just a CLI flag.
 */
@Component
public class DashboardConfigLocations {

    private final List<String> dashboards;
    private final List<String> data;

    public DashboardConfigLocations(Environment environment, ConfigPathsProperties paths) {
        List<String> dashboardsOverride = bindList(environment, "dashboards");
        List<String> dataOverride = bindList(environment, "data");
        this.dashboards = dashboardsOverride.isEmpty() ? List.of(paths.dashboardsPath()) : dashboardsOverride;
        this.data = dataOverride.isEmpty() ? List.of(paths.dataPath()) : dataOverride;
    }

    public List<String> dashboards() {
        return dashboards;
    }

    public List<String> data() {
        return data;
    }

    private static List<String> bindList(Environment environment, String propertyName) {
        return Binder.get(environment).bind(propertyName, Bindable.listOf(String.class)).orElse(List.of());
    }
}
