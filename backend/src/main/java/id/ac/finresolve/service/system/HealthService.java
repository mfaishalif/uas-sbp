package id.ac.finresolve.service.system;

import id.ac.finresolve.domain.error.BusinessException;
import id.ac.finresolve.repository.db.DatabaseBusyException;
import id.ac.finresolve.repository.system.SystemRepository;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Coordinates demo readiness and a real database probe for the health endpoint. */
@Service
public final class HealthService {

    private static final Logger LOG = LoggerFactory.getLogger(HealthService.class);

    private final DemoReadiness demoReadiness;
    private final SystemRepository repository;

    public HealthService(DemoReadiness demoReadiness, SystemRepository repository) {
        this.demoReadiness = Objects.requireNonNull(demoReadiness, "demoReadiness");
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    public void check() {
        if (!demoReadiness.isReady()) {
            throw new BusinessException(
                    "SERVICE_UNAVAILABLE",
                    "Inisialisasi data demo belum selesai.",
                    List.of());
        }
        try {
            repository.ping();
        } catch (DatabaseBusyException busy) {
            throw busy;
        } catch (RuntimeException failure) {
            LOG.error("HTTP 503 SERVICE_UNAVAILABLE ({})", failure.getClass().getName());
            throw new BusinessException(
                    "SERVICE_UNAVAILABLE",
                    "Layanan database tidak tersedia.",
                    List.of());
        }
    }
}
