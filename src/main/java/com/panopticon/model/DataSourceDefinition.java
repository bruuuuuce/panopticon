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
 */
public record DataSourceDefinition(
        String name,
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
}
