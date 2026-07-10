package com.panopticon.registry;

import javax.sql.DataSource;
import java.util.Map;
import java.util.Set;

/**
 * In-memory lookup of configured datasources by name, keyed the same way as
 * {@code panopticon.datasources.*} in application.yml and referenced by
 * {@link com.panopticon.model.QueryDefinition#datasource()}.
 *
 * <p>Exposes the raw {@link DataSource} rather than a shared JdbcTemplate:
 * per-query settings (timeout, max rows) must be applied per-execution, and
 * a single mutable JdbcTemplate instance shared across concurrent panel
 * refreshes would race on those settings.
 */
public class DatasourceRegistry {

    private final Map<String, DataSource> dataSources;

    public DatasourceRegistry(Map<String, DataSource> dataSources) {
        this.dataSources = Map.copyOf(dataSources);
    }

    public DataSource dataSourceFor(String name) {
        DataSource dataSource = dataSources.get(name);
        if (dataSource == null) {
            throw new NoSuchDatasourceException(name, dataSources.keySet());
        }
        return dataSource;
    }

    public boolean contains(String name) {
        return dataSources.containsKey(name);
    }

    public Set<String> names() {
        return dataSources.keySet();
    }

    public static class NoSuchDatasourceException extends RuntimeException {
        public NoSuchDatasourceException(String name, Set<String> known) {
            super("Unknown datasource '%s'. Configured datasources: %s".formatted(name, known));
        }
    }
}
