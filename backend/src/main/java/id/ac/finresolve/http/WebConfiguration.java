package id.ac.finresolve.http;

import com.fasterxml.jackson.annotation.JsonInclude;
import id.ac.finresolve.http.BusinessException.Problem;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.openapitools.jackson.nullable.JsonNullableJackson3Module;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.format.Formatter;
import org.springframework.format.FormatterRegistry;
import org.springframework.http.HttpHeaders;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.cfg.DateTimeFeature;

/**
 * CORS, canonical UUID conversion, and Jackson defaults for the local HTTP boundary.
 *
 * <p>Configured origins are exact strings. A wildcard is discarded, so a misconfiguration can
 * never turn into credential-free wildcard CORS.
 */
@Configuration(proxyBeanMethods = false)
public class WebConfiguration implements WebMvcConfigurer {

    static final List<String> ALLOWED_METHODS = List.of("GET", "POST", "PUT", "OPTIONS");
    static final List<String> ALLOWED_HEADERS = List.of("Content-Type", "Accept");

    private final List<String> allowedOrigins;

    public WebConfiguration(
            @Value("${finresolve.cors.allowed-origins:http://localhost:3000}")
            String configuredOrigins) {
        List<String> origins = new ArrayList<>();
        for (String origin : configuredOrigins.split(",")) {
            String trimmed = origin.trim();
            if (!trimmed.isEmpty() && !"*".equals(trimmed)) {
                origins.add(trimmed);
            }
        }
        this.allowedOrigins = origins.isEmpty() ? List.of("http://localhost:3000") : List.copyOf(origins);
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(allowedOrigins.toArray(String[]::new))
                .allowedMethods(ALLOWED_METHODS.toArray(String[]::new))
                .allowedHeaders(ALLOWED_HEADERS.toArray(String[]::new))
                .exposedHeaders(HttpHeaders.LOCATION)
                .maxAge(3600);
    }

    @Override
    public void addFormatters(FormatterRegistry registry) {
        registry.addFormatter(new CanonicalUuidFormatter());
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new EmptyQueryParameterInterceptor()).addPathPatterns("/api/**");
    }

    @Bean
    JsonMapperBuilderCustomizer finresolveJsonMapperCustomizer() {
        return builder -> builder
                .addModule(new JsonNullableJackson3Module())
                .changeDefaultPropertyInclusion(inclusion -> inclusion
                        .withValueInclusion(JsonInclude.Include.ALWAYS)
                        .withContentInclusion(JsonInclude.Include.ALWAYS))
                .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    @Bean
    FilterRegistrationBean<PreflightFilter> corsPreflightFilter() {
        FilterRegistrationBean<PreflightFilter> registration =
                new FilterRegistrationBean<>(new PreflightFilter(allowedOrigins));
        registration.addUrlPatterns("/api/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    private static final class CanonicalUuidFormatter implements Formatter<UUID> {

        @Override
        public UUID parse(String text, Locale locale) throws ParseException {
            if (text == null
                    || !text.matches(
                            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-"
                                    + "[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")) {
                throw new ParseException("UUID must use the full canonical form.", 0);
            }
            try {
                return UUID.fromString(text);
            } catch (IllegalArgumentException failure) {
                throw new ParseException("UUID must use the full canonical form.", 0);
            }
        }

        @Override
        public String print(UUID object, Locale locale) {
            return object.toString();
        }
    }

    /**
     * Spring MVC substitutes the generated defaults for an explicitly empty parameter such as
     * {@code page=}. The contract requires an empty value to be rejected, so the raw parameter map
     * is checked before argument binding.
     */
    static final class EmptyQueryParameterInterceptor implements HandlerInterceptor {

        private static final List<String> CONTRACT_PARAMETERS = List.of("page", "size", "source");

        @Override
        public boolean preHandle(
                HttpServletRequest request, HttpServletResponse response, Object handler) {
            for (String name : CONTRACT_PARAMETERS) {
                String[] values = request.getParameterMap().get(name);
                if (values == null) {
                    continue;
                }
                for (String value : values) {
                    if (value == null || value.isBlank()) {
                        throw new BusinessException(
                                400,
                                "VALIDATION_ERROR",
                                "Parameter permintaan tidak valid.",
                                List.of(new Problem(
                                        name, "INVALID_FORMAT", "Parameter tidak boleh kosong.")));
                    }
                }
            }
            return true;
        }
    }

    /**
     * Preflight is answered before MVC dispatch so the contract's 204 status and empty body are
     * deterministic. Actual requests still pass through Spring MVC's configured CORS handling.
     */
    static final class PreflightFilter extends OncePerRequestFilter {

        private final List<String> origins;

        PreflightFilter(List<String> origins) {
            this.origins = origins;
        }

        @Override
        protected void doFilterInternal(
                HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            if (!isPreflight(request)) {
                chain.doFilter(request, response);
                return;
            }

            String origin = request.getHeader(HttpHeaders.ORIGIN);
            String requestedMethod = request.getHeader(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD);
            String requestedHeaders = request.getHeader(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS);
            if (!origins.contains(origin)
                    || !ALLOWED_METHODS.contains(requestedMethod)
                    || !headersAllowed(requestedHeaders)) {
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                return;
            }

            response.setHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, origin);
            response.addHeader(HttpHeaders.VARY, HttpHeaders.ORIGIN);
            response.setHeader(
                    HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, String.join(", ", ALLOWED_METHODS));
            response.setHeader(
                    HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, String.join(", ", ALLOWED_HEADERS));
            response.setHeader(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, HttpHeaders.LOCATION);
            response.setHeader(HttpHeaders.ACCESS_CONTROL_MAX_AGE, "3600");
            response.setStatus(HttpServletResponse.SC_NO_CONTENT);
        }

        private static boolean isPreflight(HttpServletRequest request) {
            return "OPTIONS".equalsIgnoreCase(request.getMethod())
                    && request.getHeader(HttpHeaders.ORIGIN) != null
                    && request.getHeader(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD) != null;
        }

        private static boolean headersAllowed(String requestedHeaders) {
            if (requestedHeaders == null || requestedHeaders.isBlank()) {
                return true;
            }
            return Arrays.stream(requestedHeaders.split(","))
                    .map(String::trim)
                    .filter(header -> !header.isEmpty())
                    .allMatch(header -> ALLOWED_HEADERS.stream()
                            .anyMatch(allowed -> allowed.equalsIgnoreCase(header)));
        }
    }
}
