package id.ac.finresolve.service.system;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Readiness gate for an opt-in demo sample import.
 *
 * <p>When demo initialization is disabled the application is ready immediately. When it is enabled,
 * health checks stay unavailable until the initializer has committed (or replayed) the sample.
 */
@Component
public final class DemoReadiness {

    private volatile boolean ready;

    public DemoReadiness(@Value("${finresolve.demo.enabled:false}") boolean demoEnabled) {
        this.ready = !demoEnabled;
    }

    public boolean isReady() {
        return ready;
    }

    public void markReady() {
        ready = true;
    }
}
