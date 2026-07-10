package com.panopticon.config;

import com.panopticon.registry.DatasourceRegistry;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.jdbc.datasource.init.DatabasePopulatorUtils;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import javax.sql.DataSource;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds one HikariCP-pooled {@link DataSource} per entry under
 * {@code panopticon.datasources.*} and exposes them via a
 * {@link DatasourceRegistry}. Entries that declare {@code initSchema}/
 * {@code initData} (used by the bundled H2 mock datasource) are populated
 * once at startup so the app runs standalone without an external database.
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
    public DatasourceRegistry datasourceRegistry(
            Map<String, DatasourceDefinitionProperties> datasourceDefinitions,
            ResourceLoader resourceLoader) {

        if (datasourceDefinitions.isEmpty()) {
            log.warn("No datasources configured under panopticon.datasources.*");
        }

        Map<String, DataSource> dataSources = new LinkedHashMap<>();
        for (Map.Entry<String, DatasourceDefinitionProperties> entry : datasourceDefinitions.entrySet()) {
            String name = entry.getKey();
            DataSource dataSource = buildDataSource(name, entry.getValue());
            initializeIfRequested(name, dataSource, entry.getValue(), resourceLoader);
            dataSources.put(name, dataSource);
            log.info("Registered datasource '{}'", name);
        }
        return new DatasourceRegistry(dataSources);
    }

    private DataSource buildDataSource(String name, DatasourceDefinitionProperties def) {
        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setPoolName("panopticon-" + name);
        hikariConfig.setDriverClassName(def.getDriverClassName());
        hikariConfig.setJdbcUrl(def.getUrl());
        hikariConfig.setUsername(def.getUsername());
        hikariConfig.setPassword(def.getPassword());
        hikariConfig.setMaximumPoolSize(def.getMaxPoolSize());
        return new HikariDataSource(hikariConfig);
    }

    private void initializeIfRequested(
            String name, DataSource dataSource, DatasourceDefinitionProperties def, ResourceLoader resourceLoader) {

        if (def.getInitSchema() == null && def.getInitData() == null) {
            return;
        }
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
        if (def.getInitSchema() != null) {
            populator.addScript(resolve(resourceLoader, def.getInitSchema()));
        }
        if (def.getInitData() != null) {
            populator.addScript(resolve(resourceLoader, def.getInitData()));
        }
        log.info("Initializing demo schema/data for datasource '{}'", name);
        DatabasePopulatorUtils.execute(populator, dataSource);
    }

    private Resource resolve(ResourceLoader resourceLoader, String location) {
        return resourceLoader.getResource(location);
    }
}
