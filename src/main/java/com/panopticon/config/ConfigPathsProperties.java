package com.panopticon.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Filesystem locations of dashboard-as-code JSON, bound from
 * {@code panopticon.config.*} in application.yml.
 */
@ConfigurationProperties(prefix = "panopticon.config")
public record ConfigPathsProperties(String dashboardsPath, String dataPath) {

    public ConfigPathsProperties {
        if (dashboardsPath == null || dashboardsPath.isBlank()) {
            dashboardsPath = "config/dashboards";
        }
        if (dataPath == null || dataPath.isBlank()) {
            dataPath = "config/data";
        }
    }
}
