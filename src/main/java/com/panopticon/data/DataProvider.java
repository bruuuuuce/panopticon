package com.panopticon.data;

import com.panopticon.model.DataResult;

/**
 * The plugin contract every data source type implements: given an execution
 * context (a resolved {@link com.panopticon.model.DataDefinition} plus its
 * resolved {@link com.panopticon.model.DataSourceDefinition}), return a
 * {@link DataResult}. SQL/JDBC is just the first implementation
 * ({@code com.panopticon.data.jdbc.JdbcDataProvider}) — nothing about the
 * dashboard model, the REST API, or the frontend knows or cares that it
 * exists; {@link DataEngine} is the only thing that talks to providers.
 *
 * <p>Providers are plain Spring beans discovered by {@link DataProviderRegistry}
 * — there is no dynamic/external plugin loading (ServiceLoader, separate JARs,
 * PF4J) yet. That's a natural next step once this in-process contract has
 * proven itself with more than two providers, not something to build ahead of need.
 */
public interface DataProvider {

    /** The {@code provider} value in datasource/data-definition JSON this implementation handles, e.g. "jdbc". */
    String providerType();

    DataResult execute(DataExecutionContext context);
}
