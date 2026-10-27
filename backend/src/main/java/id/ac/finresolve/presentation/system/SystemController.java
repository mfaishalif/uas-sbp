package id.ac.finresolve.presentation.system;

import id.ac.finresolve.api.SystemApi;
import id.ac.finresolve.api.model.Health;
import id.ac.finresolve.presentation.mapper.ApiMapper;
import id.ac.finresolve.service.system.HealthService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Liveness plus a real database probe with no filesystem detail in the response. */
@RestController
@RequestMapping("/api")
public class SystemController implements SystemApi {

    private final HealthService health;
    private final ApiMapper mapper;

    public SystemController(HealthService health, ApiMapper mapper) {
        this.health = health;
        this.mapper = mapper;
    }

    @Override
    public ResponseEntity<Health> getHealth() {
        health.check();
        return ResponseEntity.ok(mapper.health());
    }
}
