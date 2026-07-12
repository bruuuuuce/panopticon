package com.panopticon.data.recording;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.panopticon.data.jdbc.JdbcDataProvider;
import com.panopticon.model.DataSourceDefinition;
import com.panopticon.registry.DataSourceRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.regex.Pattern;

/**
 * Loads a {@link DataRecorder} JSON Lines file into a pre-existing, versioned
 * database table so recorded dashboard data becomes queryable/reusable SQL data.
 *
 * <p>Deliberate constraints:
 * <ul>
 *   <li><b>The table must already exist at the expected schema version</b>
 *       (created by hand with {@code db/recording/recording_table_v1.sql}).
 *       An importer that silently creates tables in a production database is
 *       a footgun; this one fails with instructions instead.</li>
 *   <li><b>Writes go through a dedicated plain JDBC connection</b>, never the
 *       {@link JdbcDataProvider} pools — those are read-only by design and
 *       must stay that way. The target datasource must be configured with
 *       {@code read-only: false}.</li>
 *   <li><b>Idempotent re-import:</b> the table's natural key is
 *       {@code (source_file, source_line)}, so lines already imported are
 *       counted as duplicates and skipped, not duplicated.</li>
 * </ul>
 */
@Component
public class RecordingImporter {

    private static final Logger log = LoggerFactory.getLogger(RecordingImporter.class);

    /** The schema version this importer writes; see db/recording/*.sql. */
    public static final int SCHEMA_VERSION = 1;

    private static final Pattern SQL_IDENTIFIER = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");
    private static final int COMMIT_BATCH_SIZE = 500;

    private final DataSourceRegistry dataSourceRegistry;
    private final ObjectMapper objectMapper;

    public RecordingImporter(DataSourceRegistry dataSourceRegistry, ObjectMapper objectMapper) {
        this.dataSourceRegistry = dataSourceRegistry;
        this.objectMapper = objectMapper;
    }

    /** What happened to each line of the file: inserted, already present, or unparseable. */
    public record ImportReport(int inserted, int duplicates, int malformed) {
        public int totalLines() {
            return inserted + duplicates + malformed;
        }
    }

    public static class ImportException extends RuntimeException {
        public ImportException(String message) {
            super(message);
        }

        public ImportException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public ImportReport importFile(Path file, String datasourceName, String table) {
        if (!Files.isRegularFile(file)) {
            throw new ImportException("Recording file not found: " + file.toAbsolutePath());
        }
        if (!SQL_IDENTIFIER.matcher(table).matches()) {
            throw new ImportException("Invalid table name '%s' (letters, digits and _ only)".formatted(table));
        }
        DataSourceDefinition datasource = dataSourceRegistry.find(datasourceName)
                .orElseThrow(() -> new ImportException("Unknown datasource '%s'".formatted(datasourceName)));
        if (!JdbcDataProvider.PROVIDER_TYPE.equals(datasource.provider())) {
            throw new ImportException("Datasource '%s' is not a jdbc datasource".formatted(datasourceName));
        }
        if (datasource.readOnly()) {
            throw new ImportException(
                    "Datasource '%s' is configured read-only; importing needs a datasource with read-only: false"
                            .formatted(datasourceName));
        }

        try (Connection connection = openConnection(datasource)) {
            assertSchemaVersion(connection, table);
            return insertAll(connection, table, file);
        } catch (SQLException e) {
            throw new ImportException("Import failed: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new ImportException("Could not read " + file.toAbsolutePath() + ": " + e.getMessage(), e);
        }
    }

    private Connection openConnection(DataSourceDefinition datasource) throws SQLException {
        try {
            Class.forName(datasource.driverClassName());
        } catch (ClassNotFoundException e) {
            throw new ImportException("JDBC driver not on classpath: " + datasource.driverClassName(), e);
        }
        return DriverManager.getConnection(datasource.jdbcUrl(), datasource.username(), datasource.password());
    }

    private void assertSchemaVersion(Connection connection, String table) {
        String versionTable = table + "_version";
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT MAX(version) FROM " + versionTable)) {
            int version = rs.next() ? rs.getInt(1) : 0;
            if (version != SCHEMA_VERSION) {
                throw new ImportException(
                        "Table '%s' is at schema version %d but this importer requires version %d. See db/recording/ for the upgrade scripts."
                                .formatted(table, version, SCHEMA_VERSION));
            }
        } catch (SQLException e) {
            throw new ImportException(
                    "Target table '%s' (or its version table '%s') does not exist. Create it first with db/recording/recording_table_v%d.sql — the importer never creates tables itself."
                            .formatted(table, versionTable, SCHEMA_VERSION), e);
        }
    }

    private ImportReport insertAll(Connection connection, String table, Path file) throws SQLException, IOException {
        String sql = ("INSERT INTO %s (source_file, source_line, recorded_at, data_id, datasource, provider, "
                + "status, execution_time_ms, columns_json, rows_json, error_message, imported_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)").formatted(table);
        String sourceFile = file.getFileName().toString();
        String importedAt = Instant.now().toString();

        int inserted = 0;
        int duplicates = 0;
        int malformed = 0;

        connection.setAutoCommit(false);
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8);
             PreparedStatement insert = connection.prepareStatement(sql)) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank()) {
                    continue;
                }
                RecordedExecution execution;
                try {
                    execution = objectMapper.readValue(line, RecordedExecution.class);
                } catch (IOException e) {
                    log.warn("Skipping malformed line {} of {}: {}", lineNumber, sourceFile, e.getMessage());
                    malformed++;
                    continue;
                }
                insert.setString(1, sourceFile);
                insert.setInt(2, lineNumber);
                insert.setString(3, execution.recordedAt() != null ? execution.recordedAt().toString() : null);
                insert.setString(4, execution.dataId());
                insert.setString(5, execution.datasource());
                insert.setString(6, execution.provider());
                insert.setString(7, execution.status());
                insert.setLong(8, execution.executionTimeMs());
                insert.setString(9, objectMapper.writeValueAsString(execution.columns()));
                insert.setString(10, objectMapper.writeValueAsString(execution.rows()));
                insert.setString(11, execution.errorMessage());
                insert.setString(12, importedAt);
                try {
                    insert.executeUpdate();
                    inserted++;
                } catch (SQLException e) {
                    // Primary-key violation on (source_file, source_line): this line was
                    // imported by a previous run. Anything else is a real failure.
                    if (isDuplicateKey(e)) {
                        duplicates++;
                    } else {
                        throw e;
                    }
                }
                if ((inserted + duplicates) % COMMIT_BATCH_SIZE == 0) {
                    connection.commit();
                }
            }
            connection.commit();
        } catch (SQLException | IOException | RuntimeException e) {
            connection.rollback();
            throw e;
        }
        return new ImportReport(inserted, duplicates, malformed);
    }

    private boolean isDuplicateKey(SQLException e) {
        // SQLState 23xxx = integrity constraint violation (both H2 and SQLite's
        // xerial driver report it, SQLite additionally via the message).
        String state = e.getSQLState();
        return (state != null && state.startsWith("23"))
                || (e.getMessage() != null && e.getMessage().contains("PRIMARY KEY"));
    }
}
