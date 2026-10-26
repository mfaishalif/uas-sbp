package id.ac.finresolve.http;

import id.ac.finresolve.api.model.ApiError;
import id.ac.finresolve.db.DatabaseBusyException;
import id.ac.finresolve.http.BusinessException.Problem;
import jakarta.validation.ConstraintViolationException;
import java.beans.PropertyEditorSupport;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Turns framework and business failures into the four-key generated {@link ApiError} shape.
 *
 * <p>Messages are safe for clients. No SQL, local path, stack trace, rejected narrative, or raw
 * framework text is copied into a response or a log.
 */
@RestControllerAdvice
public final class ApiExceptionHandler {

    private static final Pattern CANONICAL_UUID = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-"
                    + "[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private final ApiMapper mapper;

    public ApiExceptionHandler(ApiMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * Java's {@link UUID#fromString(String)} accepts abbreviated groups such as {@code 1-1-1-1-1}.
     * Path variables must use the full 8-4-4-4-12 form before any SQL is reached.
     */
    @InitBinder
    public void canonicalUuid(WebDataBinder binder) {
        binder.registerCustomEditor(UUID.class, new PropertyEditorSupport() {
            @Override
            public void setAsText(String text) {
                if (text == null || !CANONICAL_UUID.matcher(text).matches()) {
                    throw new IllegalArgumentException("UUID must use the full canonical form.");
                }
                setValue(UUID.fromString(text));
            }
        });
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiError> business(BusinessException failure) {
        return ResponseEntity.status(failure.status())
                .body(mapper.error(failure, Instant.now()));
    }

    @ExceptionHandler(DatabaseBusyException.class)
    public ResponseEntity<ApiError> databaseBusy(DatabaseBusyException failure) {
        return respond(503, "DATABASE_BUSY", "Database sedang sibuk; coba kembali.", List.of());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> binding(MethodArgumentNotValidException failure) {
        List<Problem> problems = new ArrayList<>();
        for (FieldError error : failure.getBindingResult().getFieldErrors()) {
            problems.add(new Problem(
                    error.getField(),
                    validationCode(error.getCode()),
                    validationMessage(error.getCode())));
        }
        return respond(400, "VALIDATION_ERROR", "Input tidak valid.", problems);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiError> methodValidation(HandlerMethodValidationException failure) {
        List<Problem> problems = new ArrayList<>();
        for (ParameterValidationResult result : failure.getParameterValidationResults()) {
            String field = result.getMethodParameter().getParameterName();
            if (field == null || field.isBlank()) {
                field = "parameter";
            }
            for (MessageSourceResolvable error : result.getResolvableErrors()) {
                String code = firstCode(error);
                problems.add(new Problem(
                        field,
                        validationCode(code),
                        validationMessage(code)));
            }
        }
        return respond(400, "VALIDATION_ERROR", "Parameter permintaan tidak valid.", problems);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> typeMismatch(MethodArgumentTypeMismatchException failure) {
        String field = failure.getName() == null ? "parameter" : failure.getName();
        return respond(
                400,
                "MALFORMED_REQUEST",
                "Format parameter permintaan tidak valid.",
                List.of(new Problem(
                        field, "INVALID_FORMAT", "Format nilai parameter tidak sesuai.")));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiError> missingParameter(MissingServletRequestParameterException failure) {
        return respond(
                400,
                "VALIDATION_ERROR",
                "Parameter permintaan wajib diisi.",
                List.of(new Problem(
                        failure.getParameterName(), "REQUIRED", "Parameter wajib diisi.")));
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<ApiError> missingPart(MissingServletRequestPartException failure) {
        return respond(
                400,
                "VALIDATION_ERROR",
                "Bagian permintaan wajib diisi.",
                List.of(new Problem(
                        failure.getRequestPartName(), "REQUIRED", "Bagian permintaan wajib diisi.")));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> unreadable(HttpMessageNotReadableException failure) {
        return respond(
                400,
                "MALFORMED_REQUEST",
                "Badan permintaan tidak dapat dibaca.",
                List.of());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> methodNotAllowed(HttpRequestMethodNotSupportedException failure) {
        return respond(405, "METHOD_NOT_ALLOWED", "Metode HTTP tidak didukung.", List.of());
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiError> mediaType(HttpMediaTypeNotSupportedException failure) {
        return respond(
                415,
                "UNSUPPORTED_MEDIA_TYPE",
                "Tipe konten permintaan tidak didukung.",
                List.of());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiError> tooLarge(MaxUploadSizeExceededException failure) {
        return respond(
                413,
                "PAYLOAD_TOO_LARGE",
                "Ukuran unggahan melebihi batas.",
                List.of());
    }

    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<ApiError> routeNotFound(Exception failure) {
        return respond(404, "ROUTE_NOT_FOUND", "Route tidak ditemukan.", List.of());
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> constraintViolation(ConstraintViolationException failure) {
        List<Problem> problems = new ArrayList<>();
        failure.getConstraintViolations().forEach(violation -> {
            String path = violation.getPropertyPath() == null
                    ? "parameter"
                    : violation.getPropertyPath().toString();
            int separator = path.lastIndexOf('.');
            String field = separator >= 0 ? path.substring(separator + 1) : path;
            problems.add(new Problem(field, "INVALID_VALUE", "Nilai parameter tidak valid."));
        });
        return respond(400, "VALIDATION_ERROR", "Parameter permintaan tidak valid.", problems);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> unexpected(Exception failure) {
        return respond(500, "INTERNAL_ERROR", "Terjadi kesalahan internal.", List.of());
    }

    private ResponseEntity<ApiError> respond(
            int status, String code, String message, List<Problem> problems) {
        return ResponseEntity.status(status)
                .body(mapper.error(new BusinessException(status, code, message, problems), Instant.now()));
    }

    private static String firstCode(MessageSourceResolvable error) {
        String[] codes = error.getCodes();
        return codes == null || codes.length == 0 ? null : codes[0];
    }

    private static String validationCode(String code) {
        if (code == null) {
            return "INVALID_VALUE";
        }
        return switch (code) {
            case "NotNull", "NotBlank", "NotEmpty", "MissingServletRequestParameter" -> "REQUIRED";
            case "Size", "Length" -> "TOO_LONG";
            case "Min", "Max", "DecimalMin", "DecimalMax", "Range" -> "OUT_OF_RANGE";
            case "Pattern", "Email" -> "INVALID_FORMAT";
            default -> "INVALID_VALUE";
        };
    }

    private static String validationMessage(String code) {
        return switch (validationCode(code)) {
            case "REQUIRED" -> "Field wajib diisi.";
            case "TOO_LONG" -> "Panjang nilai melebihi batas.";
            case "OUT_OF_RANGE" -> "Nilai di luar rentang yang diizinkan.";
            case "INVALID_FORMAT" -> "Format nilai tidak sesuai.";
            default -> "Nilai field tidak valid.";
        };
    }
}
