package id.ac.finresolve.http;

import id.ac.finresolve.api.SystemApi;
import id.ac.finresolve.api.model.Health;
import id.ac.finresolve.db.Database;
import id.ac.finresolve.db.DatabaseBusyException;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Liveness plus a real database probe with no filesystem detail in the response. */
@RestController
@RequestMapping("/api")
public class SystemController implements SystemApi {

    private final Database database;
    private final ApiMapper mapper;

    public SystemController(Database database, ApiMapper mapper) {
        this.database = database;
        this.mapper = mapper;
    }

    @Override
    public ResponseEntity<Health> getHealth() {
        try {
            database.read(() -> database.jdbcTemplate().queryForObject("SELECT 1", Integer.class));
        } catch (DatabaseBusyException busy) {
            throw busy;
        } catch (RuntimeException failure) {
            throw new BusinessException(
                    503,
                    "SERVICE_UNAVAILABLE",
                    "Layanan database tidak tersedia.",
                    List.of());
        }
        return ResponseEntity.ok(mapper.health());
    }
}
