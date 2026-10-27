package id.ac.finresolve.repository.db;

import id.ac.finresolve.repository.TransactionOperations;
import java.sql.SQLException;
import java.util.Locale;
import java.util.function.Supplier;
import org.sqlite.SQLiteErrorCode;
import org.sqlite.SQLiteException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Short, serialized database work.
 *
 * <p>Both callbacks use the same transaction-aware {@link JdbcTemplate}. Calls nest through Spring's
 * REQUIRED propagation, so a repository called inside a caller's transaction joins that transaction.
 * Callbacks must return materialized values, never a live {@link java.sql.ResultSet}.
 */
public final class Database implements TransactionOperations {

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate readTransaction;
    private final TransactionTemplate writeTransaction;

    public Database(JdbcTemplate jdbcTemplate, PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.readTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction.setReadOnly(true);
        this.writeTransaction = new TransactionTemplate(transactionManager);
    }

    public JdbcTemplate jdbcTemplate() {
        return jdbcTemplate;
    }

    @Override
    public <T> T read(Supplier<T> callback) {
        return execute(readTransaction, callback);
    }

    @Override
    public <T> T write(Supplier<T> callback) {
        return execute(writeTransaction, callback);
    }

    private <T> T execute(TransactionTemplate template, Supplier<T> callback) {
        try {
            return template.execute(status -> callback.get());
        } catch (RuntimeException failure) {
            if (isBusy(failure)) {
                throw new DatabaseBusyException(
                        "The SQLite database is busy; retry the operation later.", failure);
            }
            throw failure;
        }
    }

    private static boolean isBusy(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof SQLiteException sqlite) {
                SQLiteErrorCode code = sqlite.getResultCode();
                if (code == SQLiteErrorCode.SQLITE_BUSY || code == SQLiteErrorCode.SQLITE_LOCKED) {
                    return true;
                }
            }
            if (current instanceof SQLException sql) {
                String state = sql.getSQLState();
                if (state != null && state.toUpperCase(Locale.ROOT).contains("BUSY")) {
                    return true;
                }
            }
            String message = current.getMessage();
            if (message != null) {
                String upper = message.toUpperCase(Locale.ROOT);
                if (upper.contains("SQLITE_BUSY")
                        || upper.contains("SQLITE_LOCKED")
                        || upper.contains("DATABASE IS LOCKED")
                        || upper.contains("DATABASE TABLE IS LOCKED")) {
                    return true;
                }
            }
        }
        return false;
    }
}
