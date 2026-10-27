package id.ac.finresolve.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import id.ac.finresolve.repository.cases.CaseRepository;
import id.ac.finresolve.repository.category.CategoryRepository;
import id.ac.finresolve.repository.db.Database;
import id.ac.finresolve.repository.db.MigrationRunner;
import id.ac.finresolve.repository.importing.ImportRepository;
import id.ac.finresolve.repository.system.SystemRepository;
import id.ac.finresolve.service.category.CategoryService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.sqlite.SQLiteConfig;

/**
 * One-connection SQLite configuration for the local demo.
 *
 * <p>The database path is resolved and its parent directory created before Hikari opens a
 * connection. PRAGMAs are part of the driver connection properties, so every connection (and the
 * first connection used by migrations) starts with foreign keys, the busy timeout, and WAL.
 */
@Configuration(proxyBeanMethods = false)
public class DatabaseConfiguration {

    public static final int BUSY_TIMEOUT_MILLIS = 5_000;

    private static final Logger LOG = LoggerFactory.getLogger(DatabaseConfiguration.class);

    @Bean
    public DatabasePath finresolveDatabasePath(
            @Value("${finresolve.db.path:data/finresolve.db}") String configuredPath)
            throws IOException {
        Path absolute = Path.of(configuredPath).toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        LOG.info("SQLite database configured at {}", absolute);
        return new DatabasePath(absolute);
    }

    @Bean
    public HikariDataSource dataSource(
            DatabasePath databasePath,
            @Value("${spring.datasource.hikari.minimum-idle:1}") int minimumIdle,
            @Value("${spring.datasource.hikari.maximum-pool-size:1}") int maximumPoolSize) {
        return createDataSource(
                "jdbc:sqlite:" + databasePath.value(), minimumIdle, maximumPoolSize);
    }

    @Bean
    public JdbcTemplate jdbcTemplate(HikariDataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean
    public DataSourceTransactionManager transactionManager(HikariDataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }

    @Bean
    public Database database(
            JdbcTemplate jdbcTemplate, DataSourceTransactionManager transactionManager) {
        return new Database(jdbcTemplate, transactionManager);
    }

    @Bean
    public CaseRepository caseRepository(Database database) {
        return new CaseRepository(database);
    }

    @Bean
    public CategoryRepository categoryRepository(Database database) {
        return new CategoryRepository(database);
    }

    @Bean
    public ImportRepository importRepository(Database database) {
        return new ImportRepository(database);
    }

    @Bean
    public SystemRepository systemRepository(Database database) {
        return new SystemRepository(database);
    }

    @Bean
    public CategoryService categoryService(CategoryRepository categoryRepository) {
        return new CategoryService(categoryRepository);
    }

    @Bean
    public MigrationRunner migrationRunner(Database database) {
        MigrationRunner runner =
                new MigrationRunner(database, MigrationRunner.productionMigrations());
        runner.migrate();
        return runner;
    }

    public static HikariDataSource createDataSource(String jdbcUrl) {
        return createDataSource(jdbcUrl, 1, 1);
    }

    public static HikariDataSource createDataSource(
            String jdbcUrl, int minimumIdle, int maximumPoolSize) {
        SQLiteConfig sqlite = new SQLiteConfig();
        sqlite.enforceForeignKeys(true);
        sqlite.setBusyTimeout(BUSY_TIMEOUT_MILLIS);
        sqlite.setJournalMode(SQLiteConfig.JournalMode.WAL);

        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName("finresolve-sqlite");
        hikari.setDriverClassName("org.sqlite.JDBC");
        hikari.setJdbcUrl(jdbcUrl);
        hikari.setMinimumIdle(minimumIdle);
        hikari.setMaximumPoolSize(maximumPoolSize);
        hikari.setDataSourceProperties(sqlite.toProperties());
        return new HikariDataSource(hikari);
    }

    public record DatabasePath(Path value) {
    }
}
