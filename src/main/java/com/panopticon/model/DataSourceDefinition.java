package com.panopticon.model;

/**
 * A configured datasource, bound from {@code panopticon.datasources.<name>.*}
 * in application.yml. Like {@link DataDefinition}, this is a flat record
 * covering both provider shapes rather than a type hierarchy — jdbc-only
 * fields ({@code jdbcUrl}, {@code driverClassName}, {@code dialect},
 * {@code readOnly}, {@code maxPoolSize}, {@code initSchema}/{@code initData})
 * and jira-only fields ({@code baseUrl}, {@code authType}, {@code authToken})
 * simply sit unused by whichever provider doesn't need them. {@code dialect}
 * is carried as a plain string here (not an enum) so this model type stays
 * provider-agnostic; {@code JdbcDataProvider} is the one place that parses it.
 *
 * <p>{@code name} is the technical config key (the map key under
 * {@code panopticon.datasources.*}, e.g. {@code "demo-h2"}) and must stay
 * stable — it's what {@link DataDefinition#datasource()} references and how
 * pools/clients are looked up. {@code displayName} is the human-facing label
 * shown wherever a connection needs to be identified to a viewer (dashboard
 * panels, the query stats page); it defaults to {@code name} when not
 * configured, so every datasource always has one without requiring extra
 * config.
 */
public record DataSourceDefinition(
        String name,
        String displayName,
        String provider,
        // JDBC
        String jdbcUrl,
        String username,
        String password,
        String driverClassName,
        String dialect,
        boolean readOnly,
        int maxPoolSize,
        String initSchema,
        String initData,
        // Jira
        String baseUrl,
        String authType,
        String authToken
) {
    public DataSourceDefinition {
        if (displayName == null || displayName.isBlank()) {
            displayName = name;
        }
    }
}
