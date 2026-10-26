package id.ac.finresolve.http;

import id.ac.finresolve.api.SystemApi;
import id.ac.finresolve.api.model.Health;
import id.ac.finresolve.db.Database;
import id.ac.finresolve.db.DatabaseBusyException;
import id.ac.finresolve.demo.DemoReadiness;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Liveness plus a real database probe with no filesystem detail in the response. */
@RestController
@RequestMapping("/api")
public class SystemController implements SystemApi {

    private static final Logger LOG = LoggerFactory.getLogger(SystemController.class);

    private final Database database;
    private final ApiMapper mapper;
    private final DemoReadiness demoReadiness;

    public SystemController(Database database, ApiMapper mapper, DemoReadiness demoReadiness) {
        this.database = database;
        this.mapper = mapper;
        this.demoReadiness = demoReadiness;
    }

    @Override
    public ResponseEntity<Health> getHealth() {
        if (!demoReadiness.isReady()) {
            throw new BusinessException(
                    503,
                    "SERVICE_UNAVAILABLE",
                    "Inisialisasi data demo belum selesai.",
                    List.of());
        }
        try {
            database.read(() -> database.jdbcTemplate().queryForObject("SELECT 1", Integer.class));
        } catch (DatabaseBusyException busy) {
            throw busy;
        } catch (RuntimeException failure) {
            LOG.error("HTTP 503 SERVICE_UNAVAILABLE ({})", failure.getClass().getName());
            throw new BusinessException(
                    503,
                    "SERVICE_UNAVAILABLE",
                    "Layanan database tidak tersedia.",
                    List.of());
        }
        return ResponseEntity.ok(mapper.health());
    }
}
