package com.panopticon.it.support;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Thin, dependency-free JDBC probe used as the "ground truth" independent
 * read path in integration tests - deliberately not going through
 * JdbcTemplate/HikariCP/DataEngine, so a test comparing "what the app
 * returned" against "what's actually in the database" isn't just comparing
 * the app against itself.
 */
public final class RawDb {

    private final String jdbcUrl;

    public RawDb(String jdbcUrl) {
        this.jdbcUrl = jdbcUrl;
    }

    public Connection connect() throws SQLException {
        return DriverManager.getConnection(jdbcUrl);
    }

    public long count(String sql, Object... args) {
        return scalar(sql, args).map(Number.class::cast).map(Number::longValue).orElse(0L);
    }

    public java.util.Optional<Object> scalar(String sql, Object... args) {
        try (Connection conn = connect(); PreparedStatement ps = prepare(conn, sql, args); ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) {
                return java.util.Optional.empty();
            }
            return java.util.Optional.ofNullable(rs.getObject(1));
        } catch (SQLException e) {
            throw new RuntimeException("RawDb query failed: " + sql, e);
        }
    }

    public List<Map<String, Object>> query(String sql, Object... args) {
        try (Connection conn = connect(); PreparedStatement ps = prepare(conn, sql, args); ResultSet rs = ps.executeQuery()) {
            ResultSetMetaData meta = rs.getMetaData();
            int columnCount = meta.getColumnCount();
            List<Map<String, Object>> rows = new ArrayList<>();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>(columnCount);
                for (int i = 1; i <= columnCount; i++) {
                    row.put(meta.getColumnLabel(i).toLowerCase(java.util.Locale.ROOT), rs.getObject(i));
                }
                rows.add(row);
            }
            return rows;
        } catch (SQLException e) {
            throw new RuntimeException("RawDb query failed: " + sql, e);
        }
    }

    public void execute(String sql, Object... args) {
        try (Connection conn = connect(); PreparedStatement ps = prepare(conn, sql, args)) {
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("RawDb statement failed: " + sql, e);
        }
    }

    private PreparedStatement prepare(Connection conn, String sql, Object... args) throws SQLException {
        PreparedStatement ps = conn.prepareStatement(sql);
        for (int i = 0; i < args.length; i++) {
            ps.setObject(i + 1, args[i]);
        }
        return ps;
    }
}
