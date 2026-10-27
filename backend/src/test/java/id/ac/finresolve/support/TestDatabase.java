package id.ac.finresolve.support;

import com.zaxxer.hikari.HikariDataSource;
import id.ac.finresolve.repository.cases.CaseRepository;
import id.ac.finresolve.config.DatabaseConfiguration;
import id.ac.finresolve.repository.db.Database;
import id.ac.finresolve.repository.db.MigrationRunner;
import id.ac.finresolve.repository.db.Timestamps;
import id.ac.finresolve.domain.importing.BatchRecord;
import id.ac.finresolve.domain.importing.ImportRowProblem;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reusable file-backed SQLite harness for database integration tests.
 *
 * <p>Never use an in-memory SQLite URL: reopen and lock tests must observe the same file from
 * independent connections.
 */
public final class TestDatabase implements AutoCloseable {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final Path file;
    private final HikariDataSource dataSource;
    private final JdbcTemplate jdbc;
    private final Database database;
    private final CaseRepository caseRepository;

    private TestDatabase(Path file, HikariDataSource dataSource) {
        this.file = file;
        this.dataSource = dataSource;
        this.jdbc = new JdbcTemplate(dataSource);
        this.database = new Database(jdbc, new DataSourceTransactionManager(dataSource));
        this.caseRepository = new CaseRepository(database);
    }

    public static TestDatabase create(Path directory) {
        return create(directory, MigrationRunner.productionMigrations());
    }

    public static TestDatabase create(Path directory, List<MigrationRunner.Migration> migrations) {
        return open(directory.resolve("finresolve.db"), migrations);
    }

    public static TestDatabase reopen(Path databaseFile) {
        return open(databaseFile, MigrationRunner.productionMigrations());
    }

    public static TestDatabase open(Path databaseFile, List<MigrationRunner.Migration> migrations) {
        Path absolute = databaseFile.toAbsolutePath().normalize();
        HikariDataSource dataSource =
                DatabaseConfiguration.createDataSource("jdbc:sqlite:" + absolute);
        TestDatabase testDatabase = new TestDatabase(absolute, dataSource);
        try {
            new MigrationRunner(testDatabase.database, migrations).migrate();
            return testDatabase;
        } catch (RuntimeException failure) {
            testDatabase.close();
            throw failure;
        }
    }

    public Path file() {
        return file;
    }

    public Database database() {
        return database;
    }

    public JdbcTemplate jdbc() {
        return jdbc;
    }

    public CaseRepository caseRepository() {
        return caseRepository;
    }

    /** Opens an independent connection with the same safety PRAGMAs, but no lock timeout. */
    public Connection openIndependentConnection() throws SQLException {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys=ON");
            statement.execute("PRAGMA busy_timeout=0");
        }
        return connection;
    }

    /** Takes the single SQLite write lock from a non-application connection. */
    public Connection acquireWriteLock() throws SQLException {
        Connection connection = openIndependentConnection();
        try (Statement statement = connection.createStatement()) {
            statement.execute("BEGIN IMMEDIATE");
            return connection;
        } catch (SQLException failure) {
            connection.close();
            throw failure;
        }
    }

    public static BatchRecord sampleBatch() {
        return new BatchRecord(
                UUID.randomUUID(),
                "https://files.consumerfinance.gov/f/documents/synthetic-archive.zip",
                "finresolve-sample.csv",
                LocalDate.parse("2024-11-01"),
                LocalDate.parse("2024-12-31"),
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                Instant.parse("2026-10-26T08:00:00Z"),
                1,
                1,
                0,
                0,
                List.of());
    }

    public UUID seedBatch() {
        return seedBatch(sampleBatch());
    }

    public UUID seedBatch(BatchRecord batch) {
        jdbc.update(
                """
                INSERT INTO import_batches (
                    id, archive_url, source_file_name, period_start, period_end, csv_sha256,
                    imported_at, processed, imported, duplicates, rejected, errors_json
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                batch.id().toString(),
                batch.archiveUrl(),
                batch.sourceFileName(),
                batch.periodStart().toString(),
                batch.periodEnd().toString(),
                batch.csvSha256(),
                Timestamps.format(batch.importedAt()),
                batch.processed(),
                batch.imported(),
                batch.duplicates(),
                batch.rejected(),
                JSON.writeValueAsString(batch.errors()));
        return batch.id();
    }

    public static ImportRowProblem problem(int recordNumber, String field, String code) {
        return new ImportRowProblem(recordNumber, field, code, "Synthetic problem");
    }

    @Override
    public void close() {
        dataSource.close();
    }
}
