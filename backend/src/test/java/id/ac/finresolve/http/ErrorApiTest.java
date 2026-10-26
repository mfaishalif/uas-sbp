package id.ac.finresolve.http;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.zaxxer.hikari.HikariDataSource;
import id.ac.finresolve.db.DatabaseBusyException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ErrorApiTest.FailureEndpoints.class)
class ErrorApiTest {

    private static final Path DATABASE_DIRECTORY = createDatabaseDirectory();
    private static final Path DATABASE_FILE = DATABASE_DIRECTORY.resolve("finresolve.db");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String SENTINEL = "SENTINEL_NARRATIVE_SQL_SELECT";
    private static final String VALID_RETAIN_BODY = """
            {
              "product": "Checking or savings account",
              "subProduct": "Checking account",
              "issue": "Managing an account",
              "subIssue": null,
              "narrative": "My transfer is missing from my checking account.",
              "receivedDate": null,
              "company": null,
              "companyResponse": null,
              "handlingNotes": "Reviewed transfer evidence and requested a trace.",
              "outcomeAssessment": "UNKNOWN",
              "evaluationNotes": null,
              "referenceCaseIds": []
            }
            """;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("finresolve.db.path", DATABASE_FILE::toString);
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + DATABASE_FILE);
    }

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    private final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach
    void resetDatabase() {
        jdbc.update("DELETE FROM case_references");
        jdbc.update("DELETE FROM analyst_notes");
        jdbc.update("DELETE FROM cases");
        jdbc.update("DELETE FROM import_batches");
    }

    @Test
    void unknownUuidIsAContractShaped404() throws Exception {
        ApiResponse response = get("/api/cases/00000000-0000-4000-8000-000000000000");

        assertGlobalError(response, 404, "CASE_NOT_FOUND");
    }

    @Test
    void malformedUuidSourcePageAndSizeAre400WithoutTouchingTheDatabase() throws Exception {
        long before = caseCount();

        for (String id : List.of(
                "not-a-uuid",
                "1-1-1-1-1",
                "11111111-1111-4111-8111-11111111111",
                "11111111-1111-4111-8111-111111111111x")) {
            ApiResponse response = get("/api/cases/" + id);
            assertThat(response.status()).as("malformed UUID %s", id).isEqualTo(400);
            assertThat(errorCode(response)).isIn("MALFORMED_REQUEST", "VALIDATION_ERROR");
        }

        for (String query : List.of(
                "source=CFPB%27%20OR%201%3D1%20--",
                "source=",
                "page=abc",
                "page=-1",
                "page=",
                "size=0",
                "size=101",
                "size=abc")) {
            ApiResponse response = get("/api/cases?" + query);
            assertThat(response.status()).as("malformed query %s", query).isEqualTo(400);
            assertThat(errorCode(response)).isIn("MALFORMED_REQUEST", "VALIDATION_ERROR");
        }

        assertThat(caseCount()).isEqualTo(before);
    }

    @Test
    void sqlLookingSourceIsRejectedAndLeavesTheDatabaseUnchanged() throws Exception {
        long before = caseCount();
        String source = URLEncoder.encode("CFPB' OR 1=1 --", StandardCharsets.UTF_8);

        ApiResponse response = get("/api/cases?source=" + source);

        assertThat(response.status()).isEqualTo(400);
        JsonNode body = assertApiErrorKeys(response);
        assertThat(body.get("code").asString()).isEqualTo("VALIDATION_ERROR");
        assertThat(body.get("fieldErrors").isArray()).isTrue();
        assertThat(body.get("fieldErrors").toString()).contains("\"field\":\"source\"");
        assertThat(caseCount()).isEqualTo(before);
    }

    @Test
    void unknownRouteWrongMethodAndWrongContentTypeUseContractErrors() throws Exception {
        assertGlobalError(get("/api/does-not-exist"), 404, "ROUTE_NOT_FOUND");
        assertGlobalError(request("PATCH", "/api/health", Map.of(), null), 405, "METHOD_NOT_ALLOWED");
        assertGlobalError(
                request("POST", "/api/cases", Map.of("Content-Type", "text/plain"), VALID_RETAIN_BODY),
                415,
                "UNSUPPORTED_MEDIA_TYPE");
    }

    @Test
    void errorBodiesHaveExactlyTheFourContractKeys() throws Exception {
        for (ApiResponse response : List.of(
                get("/api/does-not-exist"),
                get("/api/cases/00000000-0000-4000-8000-000000000000"),
                request("PATCH", "/api/health", Map.of(), null),
                request("POST", "/api/cases", Map.of("Content-Type", "text/plain"), VALID_RETAIN_BODY))) {
            JsonNode body = assertApiErrorKeys(response);
            assertThat(body.get("message").asString()).isNotBlank();
            assertThat(body.get("timestamp").asString()).endsWith("Z");
            assertThat(body.get("fieldErrors").isArray()).isTrue();
            assertThat(body.get("fieldErrors")).isEmpty();
        }
    }

    @Test
    void temporaryMutationEndpointsReturnServiceUnavailableAndDoNotWrite() throws Exception {
        long before = caseCount();

        ApiResponse created = request(
                "POST", "/api/cases", Map.of("Content-Type", "application/json"), VALID_RETAIN_BODY);
        assertGlobalError(created, 503, "SERVICE_UNAVAILABLE");

        ApiResponse replaced = request(
                "PUT",
                "/api/cases/00000000-0000-4000-8000-000000000000",
                Map.of("Content-Type", "application/json"),
                VALID_RETAIN_BODY);
        assertGlobalError(replaced, 503, "SERVICE_UNAVAILABLE");

        assertThat(caseCount()).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM analyst_notes", Long.class)).isZero();
    }

    @Test
    void wrongContentTypeStillWinsOverTheTemporary503() throws Exception {
        assertGlobalError(
                request("PUT", "/api/cases/00000000-0000-4000-8000-000000000000",
                        Map.of("Content-Type", "text/plain"), VALID_RETAIN_BODY),
                415,
                "UNSUPPORTED_MEDIA_TYPE");
    }

    @Test
    void requestRulesRejectUnknownMissingNullAndRawOverLimitFieldsOverHttp() throws Exception {
        String unknownField = VALID_RETAIN_BODY.replaceFirst(
                "\\{", "{\n  \"source\": \"ANALYST\",");
        ApiResponse unknown = postJson(unknownField);
        assertThat(unknown.status()).isEqualTo(400);
        assertThat(unknown.body()).contains("\"code\":\"UNKNOWN_FIELD\"");

        String missingRequired = VALID_RETAIN_BODY.replace(
                "\"handlingNotes\": \"Reviewed transfer evidence and requested a trace.\",", "");
        ApiResponse missing = postJson(missingRequired);
        assertThat(missing.status()).isEqualTo(400);
        assertThat(missing.body()).contains("\"field\":\"handlingNotes\"");
        assertThat(missing.body()).contains("\"code\":\"REQUIRED\"");

        String nullReferences = VALID_RETAIN_BODY.replace(
                "\"referenceCaseIds\": []", "\"referenceCaseIds\": null");
        ApiResponse nulls = postJson(nullReferences);
        assertThat(nulls.status()).isEqualTo(400);
        assertThat(nulls.body()).contains("\"field\":\"referenceCaseIds\"");

        String overLimit = VALID_RETAIN_BODY.replace(
                "\"companyResponse\": null",
                "\"companyResponse\": \"" + "x".repeat(2_001) + "\"");
        ApiResponse tooLong = postJson(overLimit);
        assertThat(tooLong.status()).isEqualTo(400);
        assertThat(tooLong.body()).contains("\"field\":\"companyResponse\"");
        assertThat(tooLong.body()).contains("\"code\":\"TOO_LONG\"");
    }

    @Test
    void requestBodiesAndErrorLogsDoNotLeakNarrativesOrInternals() throws Exception {
        String body = VALID_RETAIN_BODY.replace(
                "My transfer is missing from my checking account.", SENTINEL);
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
        try {
            ApiResponse response = postJson(body);
            assertGlobalError(response, 503, "SERVICE_UNAVAILABLE");
            assertThat(response.body()).doesNotContain(SENTINEL);
        } finally {
            root.detachAppender(appender);
        }

        String logs = appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .collect(Collectors.joining("\n"));
        assertThat(logs).doesNotContain(SENTINEL);
        assertThat(logs).doesNotContain(DATABASE_FILE.toString());
        assertThat(logs).doesNotContain("SELECT ");
        assertThat(logs).doesNotContain("SQLITE_");
    }

    @Test
    void corsPreflightUsesConfiguredOriginMethodsHeadersAndExposesLocation() throws Exception {
        ApiResponse preflight = request(
                "OPTIONS",
                "/api/cases",
                Map.of(
                        "Origin", "http://localhost:3000",
                        "Access-Control-Request-Method", "POST",
                        "Access-Control-Request-Headers", "content-type, accept"),
                null);

        assertThat(preflight.status()).isEqualTo(204);
        assertThat(preflight.body()).isEmpty();
        assertThat(preflight.header("Access-Control-Allow-Origin")).isEqualTo("http://localhost:3000");
        assertThat(preflight.header("Access-Control-Allow-Methods"))
                .contains("GET", "POST", "PUT", "OPTIONS");
        assertThat(preflight.header("Access-Control-Allow-Headers"))
                .containsIgnoringCase("Content-Type")
                .containsIgnoringCase("Accept");
        assertThat(preflight.header("Access-Control-Expose-Headers")).contains("Location");

        ApiResponse actual = request(
                "GET", "/api/health", Map.of("Origin", "http://localhost:3000"), null);
        assertThat(actual.status()).isEqualTo(200);
        assertThat(actual.header("Access-Control-Allow-Origin")).isEqualTo("http://localhost:3000");
    }

    @Test
    void disallowedCorsOriginNeverReceivesAnAllowOriginHeader() throws Exception {
        ApiResponse preflight = request(
                "OPTIONS",
                "/api/cases",
                Map.of(
                        "Origin", "http://evil.example",
                        "Access-Control-Request-Method", "POST",
                        "Access-Control-Request-Headers", "content-type"),
                null);

        assertThat(preflight.header("Access-Control-Allow-Origin")).isNull();
    }

    @Test
    void unexpectedFailuresBecomeSafe500AndBusyBecomesDatabaseBusy() throws Exception {
        assertGlobalError(get("/api/test/failure"), 500, "INTERNAL_ERROR");
        assertGlobalError(get("/api/test/busy"), 503, "DATABASE_BUSY");
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void unavailableDatabaseHealthProbeIsASafe503(@Autowired HikariDataSource dataSource) throws Exception {
        String databasePath = DATABASE_FILE.toString();
        dataSource.close();

        ApiResponse response = get("/api/health");

        assertGlobalError(response, 503, "SERVICE_UNAVAILABLE");
        assertThat(response.body())
                .doesNotContain(databasePath)
                .doesNotContain("HikariPool")
                .doesNotContain("Unable to obtain")
                .doesNotContain("at id.ac.finresolve");
    }

    private ApiResponse postJson(String body) throws Exception {
        return request("POST", "/api/cases", Map.of("Content-Type", "application/json"), body);
    }

    private JsonNode assertGlobalError(ApiResponse response, int status, String code) {
        assertThat(response.status()).isEqualTo(status);
        JsonNode body = assertApiErrorKeys(response);
        assertThat(body.get("code").asString()).isEqualTo(code);
        assertThat(body.get("fieldErrors")).isEmpty();
        return body;
    }

    private JsonNode assertApiErrorKeys(ApiResponse response) {
        JsonNode body = JSON.readTree(response.body());
        assertThat(body.isObject()).isTrue();
        assertThat(body.propertyNames())
                .containsExactlyInAnyOrder("code", "message", "fieldErrors", "timestamp");
        return body;
    }

    private String errorCode(ApiResponse response) {
        return JSON.readTree(response.body()).get("code").asString();
    }

    private long caseCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM cases", Long.class);
    }

    private ApiResponse get(String path) throws Exception {
        return request("GET", path, Map.of(), null);
    }

    private ApiResponse request(
            String method, String path, Map<String, String> headers, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + port + path));
        builder.header("Accept", "application/json");
        headers.forEach(builder::header);
        builder.method(method, body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        HttpResponse<String> response =
                client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return new ApiResponse(response.statusCode(), response.headers(), response.body());
    }

    private static Path createDatabaseDirectory() {
        try {
            return Files.createTempDirectory("finresolve-error-api");
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FailureEndpoints {

        @Bean
        FailureController failureController() {
            return new FailureController();
        }
    }

    @RestController
    @RequestMapping("/api/test")
    static class FailureController {

        @GetMapping("/failure")
        Object failure() {
            throw new IllegalStateException(
                    "SENTINEL " + SENTINEL + " SELECT secret FROM cases /tmp/finresolve.db");
        }

        @GetMapping("/busy")
        Object busy() {
            throw new DatabaseBusyException(
                    "SENTINEL database is locked /tmp/finresolve.db", null);
        }
    }

    private record ApiResponse(int status, java.net.http.HttpHeaders headers, String body) {

        String header(String name) {
            return headers.firstValue(name).orElse(null);
        }
    }
}
