package com.panopticon.query;

import com.panopticon.model.ColumnMeta;
import com.panopticon.model.QueryDefinition;
import com.panopticon.model.QueryResult;
import com.panopticon.registry.DatasourceRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Executes a {@link QueryDefinition} against its configured datasource and
 * shapes the JDBC {@link ResultSet} into the generic tabular
 * {@link QueryResult} the frontend understands. Results are cached per query
 * id (see {@link QueryResultCache}) so concurrent panel refreshes across
 * viewers/dashboards don't each re-hit the datasource.
 */
@Component
public class QueryExecutor {

    private final DatasourceRegistry datasourceRegistry;
    private final QueryResultCache resultCache;

    public QueryExecutor(DatasourceRegistry datasourceRegistry, QueryResultCache resultCache) {
        this.datasourceRegistry = datasourceRegistry;
        this.resultCache = resultCache;
    }

    public QueryResult execute(QueryDefinition query) {
        SqlGuard.assertReadOnly(query.sql());
        return resultCache.getOrCompute(query.id(), query.cacheTtlSeconds(), () -> runQuery(query));
    }

    private QueryResult runQuery(QueryDefinition query) {
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
            columns.add(new ColumnMeta(metaData.getColumnLabel(i).toLowerCase(java.util.Locale.ROOT), metaData.getColumnTypeName(i)));
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
