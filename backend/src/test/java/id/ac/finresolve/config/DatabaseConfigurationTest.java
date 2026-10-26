package id.ac.finresolve.config;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

class DatabaseConfigurationTest {

    @TempDir
    private Path tempDirectory;

    @Test
    void logsTheAbsolutePathAndOpensTheSameDatabase() throws Exception {
        DatabaseConfiguration configuration = new DatabaseConfiguration();
        Logger logger = (Logger) LoggerFactory.getLogger(DatabaseConfiguration.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            DatabaseConfiguration.DatabasePath path = configuration.finresolveDatabasePath(
                    tempDirectory.resolve("nested/finresolve.db").toString());

            assertThat(path.value()).isAbsolute().isNormalized();
            assertThat(appender.list)
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .anySatisfy(message -> assertThat(message)
                            .contains("SQLite database configured at")
                            .contains(path.value().toString()));

            try (HikariDataSource dataSource = configuration.dataSource(path, 1, 1)) {
                assertThat(dataSource.getJdbcUrl())
                        .isEqualTo("jdbc:sqlite:" + path.value());
            }
        } finally {
            logger.detachAppender(appender);
        }
    }
}
