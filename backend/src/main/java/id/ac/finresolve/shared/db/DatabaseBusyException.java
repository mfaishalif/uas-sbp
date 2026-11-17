package id.ac.finresolve.shared.db;

import id.ac.finresolve.shared.error.BusinessException;
import java.util.List;

/** A SQLite write could not acquire the database lock within the configured busy timeout. */
public class DatabaseBusyException extends BusinessException {

    public DatabaseBusyException(String message, Throwable cause) {
        super("DATABASE_BUSY", message, List.of(), cause);
    }
}
