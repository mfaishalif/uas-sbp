package id.ac.finresolve.db;

/** Startup failed because a migration could not be validated or applied transactionally. */
public class MigrationException extends RuntimeException {

    public MigrationException(String message) {
        super(message);
    }

    public MigrationException(String message, Throwable cause) {
        super(message, cause);
    }
}
