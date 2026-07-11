package com.panopticon.query;

import com.panopticon.model.ColumnMeta;
import com.panopticon.model.QueryDefinition;
import com.panopticon.model.QueryResult;
import com.panopticon.registry.DatasourceRegistry;
import com.panopticon.registry.QueryRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.stereotype.Service;

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
 * Owns the full lifecycle of running a query by id: resolve it from the
 * {@link QueryRegistry}, enforce the {@link SqlGuard} read-only check, serve
 * or populate the {@link QueryResultCache}, execute against the query's
 * datasource under its configured timeout/maxRows, and shape the JDBC
 * {@link ResultSet} into the generic tabular {@link QueryResult} the
 * frontend understands.
 *
 * <p>This is the only path SQL execution can be reached through. There is no
 * endpoint that accepts ad hoc SQL from a client — every execution starts
 * from a query id that must already resolve to a query defined in
 * {@code config/queries}, so the frontend can never submit arbitrary SQL.
 *
 * <p><b>The read-only guard is a basic safety net, not a complete SQL
 * security system.</b> It catches obviously dangerous statements by keyword,
 * but string-based checks can in principle be worked around by SQL this
 * guard doesn't anticipate. Real deployments must still point Panopticon at
 * a genuinely read-only database user, and preferably at dedicated
 * reporting views rather than raw tables, so a guard bypass has nothing
 * destructive to reach.
 */
@Service
public class QueryEngine {

    private static final Logger log = LoggerFactory.getLogger(QueryEngine.class);

    private final QueryRegistry queryRegistry;
    private final DatasourceRegistry datasourceRegistry;
    private final QueryResultCache resultCache;

    public QueryEngine(QueryRegistry queryRegistry, DatasourceRegistry datasourceRegistry, QueryResultCache resultCache) {
        this.queryRegistry = queryRegistry;
        this.datasourceRegistry = datasourceRegistry;
        this.resultCache = resultCache;
    }

    public QueryResult execute(String queryId) {
        QueryDefinition query = queryRegistry.find(queryId)
                .orElseThrow(() -> new UnknownQueryException(queryId));
        SqlGuard.assertReadOnly(query.sql());
        return resultCache.getOrCompute(query.id(), query.cacheTtlSeconds(), () -> runQuery(query));
    }

    private QueryResult runQuery(QueryDefinition query) {
        log.debug("Executing query '{}' against datasource '{}' (cache miss/expired)", query.id(), query.datasource());
        DataSource dataSource = datasourceRegistry.dataSourceFor(query.datasource());
        // A fresh JdbcTemplate per execution: queryTimeout/maxRows are instance-level
        // settings on JdbcTemplate, and this datasource may serve concurrent panel
        // refreshes with different per-query settings.
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.setQueryTimeout(query.timeoutSeconds());
        jdbcTemplate.setMaxRows(query.maxRows());
        jdbcTemplate.setFetchSize(Math.min(query.maxRows(), 500));

        long start = System.nanoTime();
        try {
            QueryResult partial = jdbcTemplate.query(
                    query.sql(), (ResultSetExtractor<QueryResult>) rs -> extract(rs, query.maxRows()));
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            return new QueryResult(partial.columns(), partial.rows(), Instant.now(), elapsedMs, partial.rows().size());
        } catch (org.springframework.dao.DataAccessException e) {
            throw new QueryExecutionException("Query '%s' failed: %s".formatted(query.id(), rootMessage(e)), e);
        }
    }

    private QueryResult extract(ResultSet rs, int maxRows) throws SQLException {
        ResultSetMetaData metaData = rs.getMetaData();
        int columnCount = metaData.getColumnCount();

        // Most databases (H2, Oracle...) fold unquoted identifiers to upper case, but
        // dashboard/query JSON authors write field references in lower case (e.g.
        // "open_tickets"). Normalize so panel options can rely on lower-case names
        // regardless of the underlying datasource's casing convention.
        List<ColumnMeta> columns = new ArrayList<>(columnCount);
        for (int i = 1; i <= columnCount; i++) {
            columns.add(new ColumnMeta(metaData.getColumnLabel(i).toLowerCase(Locale.ROOT), metaData.getColumnTypeName(i)));
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        while (rs.next() && rows.size() < maxRows) {
            Map<String, Object> row = new LinkedHashMap<>(columnCount);
            for (int i = 1; i <= columnCount; i++) {
                row.put(columns.get(i - 1).name(), normalize(rs.getObject(i)));
            }
            rows.add(row);
        }
        // generatedAt/executionTimeMs/rowCount are filled in by the caller, which has timing context.
        return new QueryResult(columns, rows, null, 0, 0);
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
}
