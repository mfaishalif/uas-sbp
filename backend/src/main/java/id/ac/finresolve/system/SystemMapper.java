package id.ac.finresolve.system;

import id.ac.finresolve.api.model.Health;
import org.springframework.stereotype.Component;

/** Maps the local system probe to the generated transport model. */
@Component
public final class SystemMapper {

    public Health health() {
        return new Health(Health.StatusEnum.UP, Health.DatabaseEnum.UP);
    }
}
