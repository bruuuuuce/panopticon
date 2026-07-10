package com.panopticon.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Filesystem locations of dashboard-as-code JSON, bound from
 * {@code panopticon.config.*} in application.yml.
 */
@ConfigurationProperties(prefix = "panopticon.config")
public record ConfigPathsProperties(String dashboardsPath, String queriesPath) {

    public ConfigPathsProperties {
        if (dashboardsPath == null || dashboardsPath.isBlank()) {
            dashboardsPath = "config/dashboards";
        }
        if (queriesPath == null || queriesPath.isBlank()) {
            queriesPath = "config/queries";
        }
    }
}
