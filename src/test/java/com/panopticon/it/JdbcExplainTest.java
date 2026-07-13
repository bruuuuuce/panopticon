package com.panopticon.it;

import com.panopticon.data.DataExecutionContext;
import com.panopticon.data.jdbc.JdbcDataProvider;
import com.panopticon.data.plan.PlanStep;
import com.panopticon.data.plan.QueryPlanResult;
import com.panopticon.model.DataDefinition;
import com.panopticon.model.DataSourceDefinition;
import com.panopticon.registry.DataSourceRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EXPLAIN capture against real, temp-file-backed H2 and SQLite databases (no
 * Spring context needed — JdbcDataProvider only needs a DataSourceRegistry
 * and a ResourceLoader). Verifies both the raw plan rows come back and that
 * the plan is parsed into structured access-path steps (table/index touched,
 * scan vs. search) — the actual "complexity statistics" this feature exists
 * to surface, not just the raw plan text.
 */
class JdbcExplainTest {

    @Test
    void sqlite_detectsFullScanVersusIndexedSearch(@org.junit.jupiter.api.io.TempDir Path dir) {
        Path dbFile = dir.resolve("explain.db");
        DataSourceDefinition datasource = sqlite("explain-ds", dbFile);
        JdbcDataProvider provider = providerFor(datasource);

        var db = new com.panopticon.it.support.RawDb("jdbc:sqlite:" + dbFile);
        db.execute("CREATE TABLE users (id INTEGER PRIMARY KEY, email TEXT, status TEXT)");
        db.execute("CREATE INDEX idx_users_status ON users(status)");

        DataDefinition fullScanQuery = jdbcDefinition("q-full-scan", "explain-ds", "SELECT * FROM users WHERE email = 'x'");
        QueryPlanResult fullScan = provider.explain(new DataExecutionContext(fullScanQuery, datasource));
        assertThat(fullScan.supported()).isTrue();
        assertThat(fullScan.planRows()).isNotEmpty();
        assertThat(fullScan.steps()).hasSize(1);
        assertThat(fullScan.steps().get(0).accessType()).isEqualTo(PlanStep.AccessType.FULL_SCAN);
        assertThat(fullScan.steps().get(0).subject()).isEqualTo("users");
        assertThat(fullScan.warnings()).anyMatch(w -> w.contains("full table scan"));

        DataDefinition indexedQuery = jdbcDefinition("q-indexed", "explain-ds", "SELECT * FROM users WHERE status = 'x'");
        QueryPlanResult indexed = provider.explain(new DataExecutionContext(indexedQuery, datasource));
        assertThat(indexed.supported()).isTrue();
        assertThat(indexed.planRows()).isNotEmpty();
        assertThat(indexed.steps()).hasSize(1);
        assertThat(indexed.steps().get(0).accessType()).isEqualTo(PlanStep.AccessType.INDEX_SEARCH);
        assertThat(indexed.steps().get(0).subject()).isEqualTo("users");
        assertThat(indexed.warnings()).isEmpty();
    }

    @Test
    void h2_detectsFullScanVersusIndexedSearch(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        DataSourceDefinition datasource = h2("explain-h2-scan");
        // DB_CLOSE_DELAY=-1 keeps this in-memory DB alive once created, so seeding the schema over
        // a direct sa/"" connection here (matching the credentials JdbcDataProvider's own pool will
        // use below) is visible to every later connection against the same jdbc:h2:mem: URL.
        try (var conn = java.sql.DriverManager.getConnection(datasource.jdbcUrl(), "sa", "");
             var st = conn.createStatement()) {
            st.execute("CREATE TABLE users (id INT PRIMARY KEY, email VARCHAR(200), status VARCHAR(20))");
            st.execute("CREATE INDEX idx_users_status ON users(status)");
        }
        JdbcDataProvider provider = providerFor(datasource);

        DataDefinition fullScanQuery = jdbcDefinition("q-h2-full-scan", "explain-h2-scan", "SELECT * FROM users WHERE email = 'x'");
        QueryPlanResult fullScan = provider.explain(new DataExecutionContext(fullScanQuery, datasource));
        assertThat(fullScan.supported()).isTrue();
        assertThat(fullScan.planRows()).isNotEmpty();
        assertThat(fullScan.steps()).hasSize(1);
        assertThat(fullScan.steps().get(0).accessType()).isEqualTo(PlanStep.AccessType.FULL_SCAN);
        assertThat(fullScan.steps().get(0).subject()).isEqualTo("PUBLIC.USERS");
        assertThat(fullScan.warnings()).anyMatch(w -> w.contains("full table scan"));

        DataDefinition indexedQuery = jdbcDefinition("q-h2-indexed", "explain-h2-scan", "SELECT * FROM users WHERE status = 'x'");
        QueryPlanResult indexed = provider.explain(new DataExecutionContext(indexedQuery, datasource));
        assertThat(indexed.supported()).isTrue();
        assertThat(indexed.steps()).hasSize(1);
        assertThat(indexed.steps().get(0).accessType()).isEqualTo(PlanStep.AccessType.INDEX_SEARCH);
        assertThat(indexed.steps().get(0).subject()).isEqualTo("PUBLIC.IDX_USERS_STATUS");
        assertThat(indexed.warnings()).isEmpty();
    }

    @Test
    void h2_informationSchemaMetaCommentIsNotSurfacedAsAStep(@org.junit.jupiter.api.io.TempDir Path dir) {
        DataSourceDefinition datasource = h2("explain-h2-meta");
        JdbcDataProvider provider = providerFor(datasource);

        DataDefinition query = jdbcDefinition("q-h2-meta", "explain-h2-meta",
                "SELECT COUNT(*) AS n FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = 'PUBLIC'");
        QueryPlanResult result = provider.explain(new DataExecutionContext(query, datasource));

        assertThat(result.supported()).isTrue();
        assertThat(result.planRows()).isNotEmpty();
        // H2 tags system-catalog reads with a bare "/* meta */" comment - not a real table/index
        // access, so it must not show up as a step or trigger a full-scan warning.
        assertThat(result.steps()).isEmpty();
        assertThat(result.warnings()).isEmpty();
    }

    @Test
    void unsupportedDialects_reportUnsupportedWithoutThrowing() {
        DataSourceDefinition generic = new DataSourceDefinition("generic-ds", null, "jdbc", "jdbc:h2:mem:generic-dialect-ds",
                "sa", "", "org.h2.Driver", "generic", true, 1, null, null, null, null, null);
        JdbcDataProvider provider = providerFor(generic);
        DataDefinition query = jdbcDefinition("q-generic", "generic-ds", "SELECT 1");

        QueryPlanResult result = provider.explain(new DataExecutionContext(query, generic));

        assertThat(result.supported()).isFalse();
        assertThat(result.planRows()).isEmpty();
        assertThat(result.unsupportedReason()).isNotBlank();
    }

    private JdbcDataProvider providerFor(DataSourceDefinition... datasources) {
        Map<String, DataSourceDefinition> byName = new java.util.LinkedHashMap<>();
        for (DataSourceDefinition ds : datasources) {
            byName.put(ds.name(), ds);
        }
        return new JdbcDataProvider(new DataSourceRegistry(byName), new DefaultResourceLoader());
    }

    private DataDefinition jdbcDefinition(String id, String datasourceName, String sql) {
        return new DataDefinition(id, id, "jdbc", datasourceName, null, null, 0, sql, null, null, null, null);
    }

    private DataSourceDefinition sqlite(String name, Path dbFile) {
        return new DataSourceDefinition(name, null, "jdbc", "jdbc:sqlite:" + dbFile,
                null, null, "org.sqlite.JDBC", "sqlite", false, 1, null, null, null, null, null);
    }

    private DataSourceDefinition h2(String name) {
        return new DataSourceDefinition(name, null, "jdbc", "jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1",
                "sa", "", "org.h2.Driver", "h2", false, 1, null, null, null, null, null);
    }
}
