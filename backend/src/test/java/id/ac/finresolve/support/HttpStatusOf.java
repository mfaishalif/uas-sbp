package id.ac.finresolve.support;

import id.ac.finresolve.domain.error.BusinessException;
import id.ac.finresolve.presentation.error.BusinessErrorStatus;

/** Test-only bridge that keeps non-presentation tests out of the presentation package. */
public final class HttpStatusOf {

    private HttpStatusOf() {
    }

    public static int of(BusinessException failure) {
        return BusinessErrorStatus.statusOf(failure);
    }
}
