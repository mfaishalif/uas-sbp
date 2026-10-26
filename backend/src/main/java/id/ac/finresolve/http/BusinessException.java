package id.ac.finresolve.http;

import java.util.List;
import java.util.Objects;

/**
 * A business failure that the HTTP boundary can translate into the generated error shape.
 *
 * <p>Messages and field problems must stay safe for clients: no narrative text, SQL, paths,
 * stack traces, or other internal detail.
 */
public final class BusinessException extends RuntimeException {

    private final int status;
    private final String code;
    private final List<Problem> fieldErrors;

    public BusinessException(int status, String code, String message, List<Problem> fieldErrors) {
        super(message);
        this.status = status;
        this.code = Objects.requireNonNull(code, "code");
        this.fieldErrors = fieldErrors == null ? List.of() : List.copyOf(fieldErrors);
    }

    public int status() {
        return status;
    }

    public int getStatus() {
        return status;
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
