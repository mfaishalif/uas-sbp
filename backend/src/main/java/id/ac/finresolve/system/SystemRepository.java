package id.ac.finresolve.system;

import id.ac.finresolve.shared.db.Database;
import java.util.Objects;

/** Executes the database probe used by the system health check. */
public final class SystemRepository {

    private final Database database;

    public SystemRepository(Database database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    /** Runs a real statement so a closed or locked database cannot report healthy. */
    public void ping() {
        database.read(() -> database.jdbcTemplate().queryForObject("SELECT 1", Integer.class));
    }
}
