package com.panopticon.registry;

import com.panopticon.model.DataSourceDefinition;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * In-memory lookup of configured datasources by name, keyed the same way as
 * {@code panopticon.datasources.*} in application.yml and referenced by
 * {@link com.panopticon.model.DataDefinition#datasource()}.
 *
 * <p>Holds only the {@link DataSourceDefinition} config, not any live
 * connection/client resource — a datasource might be a JDBC pool, a Jira
 * REST client, or something else entirely, and only the matching
 * {@link com.panopticon.data.DataProvider} knows how to turn config into a
 * live resource. Each provider builds and caches whatever it needs
 * (e.g. {@code JdbcDataProvider} builds one {@code HikariDataSource} per
 * jdbc-provider entry) by filtering this registry at startup.
 */
public class DataSourceRegistry {

    private final Map<String, DataSourceDefinition> datasources;

    public DataSourceRegistry(Map<String, DataSourceDefinition> datasources) {
        this.datasources = Map.copyOf(datasources);
    }

    public Optional<DataSourceDefinition> find(String name) {
        return Optional.ofNullable(datasources.get(name));
    }

    public boolean contains(String name) {
        return datasources.containsKey(name);
    }

    public Set<String> names() {
        return datasources.keySet();
    }

    public Collection<DataSourceDefinition> all() {
        return datasources.values();
    }

    public static class NoSuchDataSourceException extends RuntimeException {
        public NoSuchDataSourceException(String name, Set<String> known) {
            super("Unknown datasource '%s'. Configured datasources: %s".formatted(name, known));
        }
    }
}
