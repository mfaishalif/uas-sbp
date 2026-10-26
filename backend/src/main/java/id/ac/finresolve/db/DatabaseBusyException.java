package id.ac.finresolve.db;

/** A SQLite write could not acquire the database lock within the configured busy timeout. */
public class DatabaseBusyException extends RuntimeException {

    public DatabaseBusyException(String message, Throwable cause) {
        super(message, cause);
    }
}
