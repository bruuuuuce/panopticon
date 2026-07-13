package com.panopticon.config;

import com.panopticon.model.DataSourceDefinition;
import com.panopticon.registry.DataSourceRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Converts {@code panopticon.datasources.*} YAML into immutable
 * {@link DataSourceDefinition} config records, exposed via a
 * {@link DataSourceRegistry}. This is config only — no live connection or
 * client is built here. Each {@link com.panopticon.data.DataProvider}
 * (e.g. {@code JdbcDataProvider}) builds and owns whatever live resource it
 * needs by filtering this registry for its own {@code provider} value at
 * startup, so a jdbc pool and a jira client never have to fit the same
 * "live resource" shape.
 */
@Configuration
public class DatasourceConfig {

    private static final Logger log = LoggerFactory.getLogger(DatasourceConfig.class);

    @Bean
    @ConfigurationProperties(prefix = "panopticon.datasources")
    public Map<String, DatasourceDefinitionProperties> datasourceDefinitions() {
        return new LinkedHashMap<>();
    }

    @Bean
    public DataSourceRegistry dataSourceRegistry(Map<String, DatasourceDefinitionProperties> datasourceDefinitions) {
        if (datasourceDefinitions.isEmpty()) {
            log.warn("No datasources configured under panopticon.datasources.*");
        }

        Map<String, DataSourceDefinition> definitions = new LinkedHashMap<>();
        for (Map.Entry<String, DatasourceDefinitionProperties> entry : datasourceDefinitions.entrySet()) {
            definitions.put(entry.getKey(), toDefinition(entry.getKey(), entry.getValue()));
        }
        return new DataSourceRegistry(definitions);
    }

    private DataSourceDefinition toDefinition(String name, DatasourceDefinitionProperties props) {
        DatasourceDefinitionProperties.JiraAuthProperties auth = props.getAuth();
        return new DataSourceDefinition(
                name,
                props.getDisplayName(),
                props.getProvider(),
                props.getJdbcUrl(),
                props.getUsername(),
                props.getPassword(),
                props.getDriverClassName(),
                props.getDialect(),
                props.isReadOnly(),
                props.getMaxPoolSize(),
                props.getInitSchema(),
                props.getInitData(),
                props.getBaseUrl(),
                auth == null ? null : auth.getType(),
                auth == null ? null : auth.getToken()
        );
    }
}
