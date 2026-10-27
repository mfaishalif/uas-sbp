package id.ac.finresolve.presentation.error;

import id.ac.finresolve.api.model.ApiError;
import id.ac.finresolve.presentation.mapper.ApiMapper;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Container-level fallback for errors that never reach a controller, such as a filter failure or
 * an error dispatch. It returns the same generated four-key body and never copies exception text.
 */
@RestController
@RequestMapping("/error")
public final class ApiErrorController implements ErrorController {

    private final ApiMapper mapper;

    public ApiErrorController(ApiMapper mapper) {
        this.mapper = mapper;
    }

    @RequestMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiError> error(HttpServletRequest request) {
        Object attribute = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        int status = attribute instanceof Integer value ? value : 500;
        return switch (status) {
            case 404 -> body(404, "ROUTE_NOT_FOUND", "Route tidak ditemukan.");
            case 405 -> body(405, "METHOD_NOT_ALLOWED", "Metode HTTP tidak didukung.");
            case 413 -> body(413, "PAYLOAD_TOO_LARGE", "Ukuran unggahan melebihi batas.");
            case 415 -> body(
                    415, "UNSUPPORTED_MEDIA_TYPE", "Tipe konten permintaan tidak didukung.");
            case 400 -> body(400, "MALFORMED_REQUEST", "Permintaan tidak dapat diproses.");
            case 503 -> body(
                    503, "SERVICE_UNAVAILABLE", "Layanan sedang tidak tersedia.");
            default -> body(500, "INTERNAL_ERROR", "Terjadi kesalahan internal.");
        };
    }

    private ResponseEntity<ApiError> body(int status, String code, String message) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(mapper.globalError(code, message, Instant.now()));
    }
}
