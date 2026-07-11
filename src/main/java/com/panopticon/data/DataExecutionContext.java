package com.panopticon.data;

import com.panopticon.model.DataDefinition;
import com.panopticon.model.DataSourceDefinition;

/**
 * Everything a {@link DataProvider} needs to run one execution: the data
 * definition itself and its already-resolved datasource. Resolving the
 * datasource is {@link DataEngine}'s job, not the provider's — a provider
 * should never need to know how datasources are looked up or configured.
 */
public record DataExecutionContext(DataDefinition definition, DataSourceDefinition datasource) {
}
