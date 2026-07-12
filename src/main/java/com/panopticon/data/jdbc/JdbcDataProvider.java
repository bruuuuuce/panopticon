package com.panopticon.data.jdbc;

import com.panopticon.data.DataExecutionContext;
import com.panopticon.data.DataProvider;
import com.panopticon.model.ColumnDefinition;
import com.panopticon.model.DataDefinition;
import com.panopticon.model.DataResult;
import com.panopticon.model.DataSourceDefinition;
import com.panopticon.registry.DataSourceRegistry;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.datasource.init.DatabasePopulatorUtils;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Executes SQL data definitions against any {@code provider: jdbc} datasource
 * — H2, SQLite, Oracle, or anything else with a JDBC driver on the classpath.
 * Owns the full jdbc-specific lifecycle: building one HikariCP pool per jdbc
 * datasource at startup (including running {@code initSchema}/{@code initData}
 * for demo datasources), enforcing the read-only guard, timeout and maxRows,
 * and shaping a JDBC {@link ResultSet} into the generic {@link DataResult}.
 *
 * <p>Nothing outside this class (and {@code SqlGuard}/{@code SqlDialect})
 * knows SQL exists — {@code DataEngine} and the REST layer only ever see
 * {@link DataResult}.
 */
@Component
public class JdbcDataProvider implements DataProvider {

    private static final Logger log = LoggerFactory.getLogger(JdbcDataProvider.class);
    public static final String PROVIDER_TYPE = "jdbc";

    private final Map<String, DataSource> pools;

    public JdbcDataProvider(DataSourceRegistry dataSourceRegistry, ResourceLoader resourceLoader) {
        Map<String, DataSource> built = new LinkedHashMap<>();
        for (DataSourceDefinition datasource : dataSourceRegistry.all()) {
            if (!PROVIDER_TYPE.equals(datasource.provider())) {
                continue;
            }
            DataSource pool = buildPool(datasource);
            initializeIfRequested(datasource, pool, resourceLoader);
            built.put(datasource.name(), pool);
            log.info("Registered jdbc datasource '{}' (dialect={}, readOnly={})",
                    datasource.name(), SqlDialect.fromConfig(datasource.dialect()), datasource.readOnly());
        }
        this.pools = Map.copyOf(built);
    }

    /**
     * The pools are built by this class, not registered as Spring beans, so
     * Spring won't close them on context shutdown by itself — without this,
     * every context restart (tests, devtools) leaks pool threads/connections.
     */
    @PreDestroy
    void closePools() {
        for (DataSource pool : pools.values()) {
            if (pool instanceof HikariDataSource hikari) {
                hikari.close();
            }
        }
    }

    @Override
    public String providerType() {
        return PROVIDER_TYPE;
    }

    @Override
    public boolean supports(DataDefinition definition) {
        return PROVIDER_TYPE.equals(definition.provider());
    }

    @Override
    public DataResult execute(DataExecutionContext context) {
        DataDefinition definition = context.definition();
        SqlGuard.assertReadOnly(definition.sql());

        DataSource pool = pools.get(context.datasource().name());
        if (pool == null) {
            // Startup only builds a pool for datasources with provider=jdbc; reaching here means
            // a data definition's datasource is configured for a different provider entirely.
            return DataResult.error("Datasource '%s' is not a jdbc datasource".formatted(context.datasource().name()));
        }

        log.debug("Executing data definition '{}' against datasource '{}' (cache miss/expired)",
                definition.id(), context.datasource().name());

        JdbcTemplate jdbcTemplate = new JdbcTemplate(pool);
        // A fresh JdbcTemplate per execution: queryTimeout/maxRows are instance-level settings,
        // and this datasource may serve concurrent panel refreshes with different per-definition settings.
        int timeoutSeconds = Math.max(1, definition.timeoutMs() / 1000);
        jdbcTemplate.setQueryTimeout(timeoutSeconds);
        jdbcTemplate.setMaxRows(definition.maxRows());
        jdbcTemplate.setFetchSize(Math.min(definition.maxRows(), 500));

        long start = System.nanoTime();
        try {
            DataResult partial = jdbcTemplate.query(
                    definition.sql(), (ResultSetExtractor<DataResult>) rs -> extract(rs, definition.maxRows()));
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            return DataResult.ok(partial.columns(), partial.rows(), Instant.now(), elapsedMs);
        } catch (org.springframework.dao.DataAccessException e) {
            return DataResult.error("Data definition '%s' failed: %s".formatted(definition.id(), rootMessage(e)));
        }
    }

    private DataResult extract(ResultSet rs, int maxRows) throws SQLException {
        ResultSetMetaData metaData = rs.getMetaData();
        int columnCount = metaData.getColumnCount();

        // Most databases (H2, Oracle...) fold unquoted identifiers to upper case, but
        // dashboard/data JSON authors write field references in lower case (e.g.
        // "open_tickets"). Normalize so panel options can rely on lower-case names
        // regardless of the underlying datasource's casing convention.
        List<ColumnDefinition> columns = new ArrayList<>(columnCount);
        for (int i = 1; i <= columnCount; i++) {
            columns.add(new ColumnDefinition(metaData.getColumnLabel(i).toLowerCase(Locale.ROOT), metaData.getColumnTypeName(i)));
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        while (rs.next() && rows.size() < maxRows) {
            Map<String, Object> row = new LinkedHashMap<>(columnCount);
            for (int i = 1; i <= columnCount; i++) {
                row.put(columns.get(i - 1).name(), normalize(rs.getObject(i)));
            }
            rows.add(row);
        }
        // generatedAt/executionTimeMs are filled in by the caller, which has timing context.
        return DataResult.ok(columns, rows, null, 0);
    }

    /** JDBC temporal types don't serialize to sane JSON via Jackson out of the box; normalize to java.time. */
    private Object normalize(Object value) {
        if (value instanceof java.sql.Timestamp ts) {
            return ts.toInstant();
        }
        if (value instanceof java.sql.Date d) {
            return d.toLocalDate();
        }
        if (value instanceof java.sql.Time t) {
            return t.toLocalTime();
        }
        return value;
    }

    private String rootMessage(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getMessage();
    }

    private DataSource buildPool(DataSourceDefinition datasource) {
        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setPoolName("panopticon-" + datasource.name());
        hikariConfig.setDriverClassName(datasource.driverClassName());
        hikariConfig.setJdbcUrl(datasource.jdbcUrl());
        hikariConfig.setUsername(datasource.username());
        hikariConfig.setPassword(datasource.password());
        hikariConfig.setMaximumPoolSize(datasource.maxPoolSize());
        // Applied pool-wide, at pool-build time: every borrowed connection gets
        // Connection.setReadOnly(true), an extra layer under the SQL guard. A
        // datasource that needs write access for its own demo seeding (see
        // initializeIfRequested below) must be configured readOnly: false.
        hikariConfig.setReadOnly(datasource.readOnly());
        return new HikariDataSource(hikariConfig);
    }

    private void initializeIfRequested(DataSourceDefinition datasource, DataSource pool, ResourceLoader resourceLoader) {
        if (datasource.initSchema() == null && datasource.initData() == null) {
            return;
        }
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
        if (datasource.initSchema() != null) {
            populator.addScript(resolve(resourceLoader, datasource.initSchema()));
        }
        if (datasource.initData() != null) {
            populator.addScript(resolve(resourceLoader, datasource.initData()));
        }
        log.info("Initializing demo schema/data for datasource '{}'", datasource.name());
        DatabasePopulatorUtils.execute(populator, pool);
    }

    private Resource resolve(ResourceLoader resourceLoader, String location) {
        return resourceLoader.getResource(location);
    }
}
