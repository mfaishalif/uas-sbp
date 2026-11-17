package id.ac.finresolve.shared.error;

import id.ac.finresolve.api.model.ApiError;
import id.ac.finresolve.api.model.FieldError;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/** Maps business failures to the generated four-key error body. */
@Component
public final class ErrorMapper {

    public ApiError error(BusinessException failure, Instant timestamp) {
        List<FieldError> fieldErrors = new ArrayList<>(failure.fieldErrors().size());
        for (BusinessException.Problem problem : failure.fieldErrors()) {
            fieldErrors.add(new FieldError(
                    problem.field(),
                    FieldError.CodeEnum.fromValue(problem.code()),
                    problem.message()));
        }
        return new ApiError(
                ApiError.CodeEnum.fromValue(failure.code()),
                failure.getMessage(),
                fieldErrors,
                OffsetDateTime.ofInstant(timestamp, ZoneOffset.UTC));
    }

    public ApiError globalError(String code, String message, Instant timestamp) {
        return new ApiError(
                ApiError.CodeEnum.fromValue(code),
                message,
                List.of(),
                OffsetDateTime.ofInstant(timestamp, ZoneOffset.UTC));
    }
}
