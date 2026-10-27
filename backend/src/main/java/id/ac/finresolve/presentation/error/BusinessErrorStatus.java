package id.ac.finresolve.presentation.error;

import id.ac.finresolve.domain.error.BusinessException;

/** Maps domain error codes to the HTTP status used by the public contract. */
public final class BusinessErrorStatus {

    private BusinessErrorStatus() {
    }

    public static int statusOf(BusinessException failure) {
        return statusOf(failure.code());
    }

    public static int statusOf(String code) {
        return switch (code) {
            case "CASE_NOT_FOUND", "ROUTE_NOT_FOUND" -> 404;
            case "METHOD_NOT_ALLOWED" -> 405;
            case "PAYLOAD_TOO_LARGE" -> 413;
            case "UNSUPPORTED_MEDIA_TYPE" -> 415;
            case "EMPTY_CATEGORY_CATALOG",
                    "IMMUTABLE_CFPB_CASE",
                    "KNOWLEDGE_BASE_LIMIT_EXCEEDED" -> 409;
            case "DATABASE_BUSY", "SERVICE_UNAVAILABLE" -> 503;
            case "INTERNAL_ERROR" -> 500;
            default -> 400;
        };
    }

    /** Keeps the stable client-facing message for infrastructure failures. */
    public static String messageOf(BusinessException failure) {
        return "DATABASE_BUSY".equals(failure.code())
                ? "Database sedang sibuk; coba kembali."
                : failure.getMessage();
    }
}
