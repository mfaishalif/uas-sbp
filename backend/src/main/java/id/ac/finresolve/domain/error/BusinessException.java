package id.ac.finresolve.domain.error;

import java.util.List;
import java.util.Objects;

/**
 * A business or operational failure with a stable code and safe client-facing details.
 *
 * <p>The domain deliberately does not know HTTP status codes. The presentation layer maps the
 * code to a response status. Messages and field problems must stay safe for clients: no narrative
 * text, SQL, paths, stack traces, or other internal detail.
 */
public class BusinessException extends RuntimeException {

    private final String code;
    private final List<Problem> fieldErrors;

    public BusinessException(String code, String message, List<Problem> fieldErrors) {
        super(message);
        this.code = Objects.requireNonNull(code, "code");
        this.fieldErrors = fieldErrors == null ? List.of() : List.copyOf(fieldErrors);
    }

    public BusinessException(
            String code, String message, List<Problem> fieldErrors, Throwable cause) {
        super(message, cause);
        this.code = Objects.requireNonNull(code, "code");
        this.fieldErrors = fieldErrors == null ? List.of() : List.copyOf(fieldErrors);
    }

    public String code() {
        return code;
    }

    public String getCode() {
        return code;
    }

    public List<Problem> fieldErrors() {
        return fieldErrors;
    }

    public List<Problem> getFieldErrors() {
        return fieldErrors;
    }

    /** One safe, field-scoped validation problem. */
    public record Problem(String field, String code, String message) {

        public Problem {
            Objects.requireNonNull(field, "field");
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(message, "message");
        }
    }
}
