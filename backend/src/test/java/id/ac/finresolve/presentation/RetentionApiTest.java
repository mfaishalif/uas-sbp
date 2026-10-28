package id.ac.finresolve.presentation;

import static org.assertj.core.api.Assertions.assertThat;

import id.ac.finresolve.domain.cases.CaseRecord;
import id.ac.finresolve.domain.category.CategoryValues;
import id.ac.finresolve.repository.db.MigrationRunner;
import id.ac.finresolve.support.TestDatabase;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RetentionApiTest {

    private static final Path DATABASE_DIRECTORY = createDatabaseDirectory();
    private static final Path DATABASE_FILE = DATABASE_DIRECTORY.resolve("finresolve.db");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final CategoryValues CHECKING = new CategoryValues(
            "Checking or savings account", "Checking account", "Managing an account", null);
    private static final String FULL_BODY = """
            {
              "product": "Checking or savings account",
              "subProduct": "Checking account",
              "issue": "Managing an account",
              "subIssue": null,
              "narrative": "My transfer is missing from my checking account.",
              "receivedDate": "2024-12-01",
              "company": "Example Bank",
              "companyResponse": "Closed with explanation",
              "handlingNotes": "Reviewed transfer evidence.",
              "outcomeAssessment": "UNKNOWN",
              "evaluationNotes": "Awaiting confirmation.",
              "referenceCaseIds": []
            }
            """;
    private static final String MINIMAL_REPLACEMENT = """
            {
              "product": "Checking or savings account",
              "issue": "Managing an account",
              "narrative": "A replacement narrative with only required fields.",
              "handlingNotes": "Replacement handling.",
              "outcomeAssessment": "RESOLVED"
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
    void postReturns201WithExactRelativeLocationAndCompleteDetail() throws Exception {
        UUID referenceId = seedCfpb("A transfer is missing from my checking account.");
        String body = withReferences(referenceId);

        ApiResponse response = request("POST", "/api/cases", body);

        assertThat(response.status()).isEqualTo(201);
        assertThat(response.contentType()).startsWith("application/json");
        JsonNode created = JSON.readTree(response.body());
        UUID id = UUID.fromString(created.get("id").asString());
        assertThat(response.location()).isEqualTo("/api/cases/" + id);
        assertThat(created.get("source").asString()).isEqualTo("ANALYST");
        assertThat(created.get("provenance").isNull()).isTrue();
        assertThat(created.get("narrative").asString())
                .isEqualTo("My transfer is missing from my checking account.");
        assertThat(created.get("receivedDate").asString()).isEqualTo("2024-12-01");
        assertThat(created.get("company").asString()).isEqualTo("Example Bank");
        assertThat(created.get("companyResponse").asString())
                .isEqualTo("Closed with explanation");
        assertThat(created.get("createdAt").asString()).endsWith("Z");
        assertThat(created.get("updatedAt").asString()).endsWith("Z");
        assertThat(created.get("analystNotes").get("handlingNotes").asString())
                .isEqualTo("Reviewed transfer evidence.");
        assertThat(created.get("analystNotes").get("outcomeAssessment").asString())
                .isEqualTo("UNKNOWN");
        assertThat(created.get("referenceCaseIds")).hasSize(1);
        assertThat(created.get("referenceCaseIds").get(0).asString())
                .isEqualTo(referenceId.toString());

        ApiResponse detail = request("GET", response.location(), null);
        assertThat(detail.status()).isEqualTo(200);
        assertThat(JSON.readTree(detail.body()).get("id").asString()).isEqualTo(id.toString());
    }

    @Test
    void fullPutReplacesTheCaseAndClearsOmittedOptionalFields() throws Exception {
        UUID referenceId = seedCfpb("A transfer is missing from my checking account.");
        ApiResponse createdResponse = request("POST", "/api/cases", withReferences(referenceId));
        assertThat(createdResponse.status()).isEqualTo(201);
        JsonNode created = JSON.readTree(createdResponse.body());
        String location = createdResponse.location();

        ApiResponse response = request("PUT", location, MINIMAL_REPLACEMENT);

        assertThat(response.status()).isEqualTo(200);
        JsonNode replaced = JSON.readTree(response.body());
        assertThat(replaced.get("id").asString()).isEqualTo(created.get("id").asString());
        assertThat(replaced.get("source").asString()).isEqualTo("ANALYST");
        assertThat(replaced.get("createdAt").asString())
                .isEqualTo(created.get("createdAt").asString());
        assertThat(Instant.parse(replaced.get("updatedAt").asString()))
                .isAfterOrEqualTo(Instant.parse(created.get("updatedAt").asString()));
        assertThat(replaced.get("narrative").asString())
                .isEqualTo("A replacement narrative with only required fields.");
        assertThat(replaced.get("subProduct").isNull()).isTrue();
        assertThat(replaced.get("receivedDate").isNull()).isTrue();
        assertThat(replaced.get("company").isNull()).isTrue();
        assertThat(replaced.get("companyResponse").isNull()).isTrue();
        assertThat(replaced.get("referenceCaseIds")).isEmpty();
        assertThat(replaced.get("analystNotes").get("handlingNotes").asString())
                .isEqualTo("Replacement handling.");
        assertThat(replaced.get("analystNotes").get("outcomeAssessment").asString())
                .isEqualTo("RESOLVED");
        assertThat(replaced.get("analystNotes").get("evaluationNotes").isNull()).isTrue();

        JsonNode persisted = JSON.readTree(request("GET", location, null).body());
        assertThat(persisted.get("company").isNull()).isTrue();
        assertThat(persisted.get("referenceCaseIds")).isEmpty();
    }

    @Test
    void explicitNullReferenceCaseIdsAndUnknownFieldsAre400() throws Exception {
        seedCfpb("A transfer is missing from my checking account.");
        String nullReferences = FULL_BODY.replace(
                "\"referenceCaseIds\": []", "\"referenceCaseIds\": null");

        ApiResponse nullResponse = request("POST", "/api/cases", nullReferences);

        assertThat(nullResponse.status()).isEqualTo(400);
        JsonNode nullError = JSON.readTree(nullResponse.body());
        assertThat(nullError.get("code").asString()).isEqualTo("VALIDATION_ERROR");
        assertThat(nullError.get("fieldErrors"))
                .anySatisfy(problem -> {
                    assertThat(problem.get("field").asString()).isEqualTo("referenceCaseIds");
                    assertThat(problem.get("code").asString()).isEqualTo("INVALID_VALUE");
                });

        for (String field : List.of("source", "id", "provenance")) {
            String unknownFieldBody = "{\n  \"" + field + "\": \"forbidden\",\n"
                    + FULL_BODY.substring(FULL_BODY.indexOf('{') + 1);
            ApiResponse response = request("POST", "/api/cases", unknownFieldBody);

            assertThat(response.status()).as(field).isEqualTo(400);
            JsonNode error = JSON.readTree(response.body());
            assertThat(error.get("code").asString()).isEqualTo("VALIDATION_ERROR");
            assertThat(error.get("fieldErrors"))
                    .anySatisfy(problem -> {
                        assertThat(problem.get("field").asString()).isEqualTo(field);
                        assertThat(problem.get("code").asString()).isEqualTo("UNKNOWN_FIELD");
                    });
        }
        assertThat(caseCount()).isEqualTo(1);
    }

    @Test
    void invalidReferencesAndCfpbMutationsAreRejectedWithoutWrites() throws Exception {
        UUID cfpbId = seedCfpb("A transfer is missing from my checking account.");

        ApiResponse invalidReference = request(
                "POST", "/api/cases", withReferences(UUID.randomUUID()));
        assertThat(invalidReference.status()).isEqualTo(400);
        assertThat(JSON.readTree(invalidReference.body()).get("code").asString())
                .isEqualTo("INVALID_REFERENCE");
        assertThat(caseCount()).isEqualTo(1);

        ApiResponse immutable = request("PUT", "/api/cases/" + cfpbId, MINIMAL_REPLACEMENT);
        assertThat(immutable.status()).isEqualTo(409);
        assertThat(JSON.readTree(immutable.body()).get("code").asString())
                .isEqualTo("IMMUTABLE_CFPB_CASE");

        ApiResponse missing = request(
                "PUT", "/api/cases/00000000-0000-4000-8000-000000000000", MINIMAL_REPLACEMENT);
        assertThat(missing.status()).isEqualTo(404);
        assertThat(JSON.readTree(missing.body()).get("code").asString())
                .isEqualTo("CASE_NOT_FOUND");

        JsonNode unchanged = JSON.readTree(request("GET", "/api/cases/" + cfpbId, null).body());
        assertThat(unchanged.get("source").asString()).isEqualTo("CFPB");
        assertThat(unchanged.get("narrative").asString())
                .isEqualTo("A transfer is missing from my checking account.");
        assertThat(caseCount()).isEqualTo(1);
    }

    @Test
    void repeatedPostCreatesTwoCases() throws Exception {
        seedCfpb("A transfer is missing from my checking account.");

        ApiResponse first = request("POST", "/api/cases", FULL_BODY);
        ApiResponse second = request("POST", "/api/cases", FULL_BODY);

        assertThat(first.status()).isEqualTo(201);
        assertThat(second.status()).isEqualTo(201);
        assertThat(first.location()).isNotEqualTo(second.location());
        assertThat(caseCount()).isEqualTo(3);
    }

    @Test
    void postRejectsNulAndLoneSurrogatesInEveryTextFieldWithoutWriting() throws Exception {
        seedCfpb("A transfer is missing from my checking account.");
        long before = caseCount();
        List<String[]> attempts = List.of(
                new String[] {"narrative", "\"transfer\\u0000account\""},
                new String[] {"narrative", "\"transfer\\ud800account\""},
                new String[] {"product", "\"\\u0000Checking account\""},
                new String[] {"issue", "\"Managing\\udfff an account\""},
                new String[] {"company", "\"Company\\u0000\""},
                new String[] {"companyResponse", "\"\\ud800Closed\""},
                new String[] {"handlingNotes", "\"Notes\\u0000\""},
                new String[] {"evaluationNotes", "\"Eval\\ud800\""});

        for (String[] attempt : attempts) {
            String body = withRawField(FULL_BODY, attempt[0], attempt[1]);
            ApiResponse response = request("POST", "/api/cases", body);

            assertThat(response.status()).as(attempt[0]).isEqualTo(400);
            JsonNode error = JSON.readTree(response.body());
            assertThat(error.get("code").asString()).isEqualTo("VALIDATION_ERROR");
            assertThat(error.get("fieldErrors")).anySatisfy(problem -> {
                assertThat(problem.get("field").asString()).isEqualTo(attempt[0]);
                assertThat(problem.get("code").asString()).isEqualTo("INVALID_FORMAT");
            });
            assertThat(response.body()).doesNotContain("transfer");
        }
        assertThat(caseCount()).isEqualTo(before);
    }

    @Test
    void fullPutRejectsInvalidUnicodeAndPreservesThePreviousCase() throws Exception {
        seedCfpb("A transfer is missing from my checking account.");
        ApiResponse created = request("POST", "/api/cases", FULL_BODY);
        assertThat(created.status()).isEqualTo(201);
        String location = created.location();
        JsonNode before = JSON.readTree(request("GET", location, null).body());

        for (String[] attempt : List.of(
                new String[] {"narrative", "\"Replacement\\u0000narrative\""},
                new String[] {"handlingNotes", "\"Replacement\\ud800 notes\""},
                new String[] {"evaluationNotes", "\"Replacement\\udfff evaluation\""})) {
            ApiResponse response = request(
                    "PUT", location, withRawField(FULL_BODY, attempt[0], attempt[1]));
            assertThat(response.status()).isEqualTo(400);
            JsonNode error = JSON.readTree(response.body());
            assertThat(error.get("code").asString()).isEqualTo("VALIDATION_ERROR");
            assertThat(error.get("fieldErrors")).anySatisfy(problem -> {
                assertThat(problem.get("field").asString()).isEqualTo(attempt[0]);
                assertThat(problem.get("code").asString()).isEqualTo("INVALID_FORMAT");
            });
            assertThat(JSON.readTree(request("GET", location, null).body())).isEqualTo(before);
        }
    }

    @Test
    void validUnicodeSurrogatePairsTabsAndNewlinesRoundTrip() throws Exception {
        seedCfpb("A transfer is missing from my checking account.");
        String narrative = "Line one 😀\\twith tab.\\nLine two 𝄞.";
        String handlingNotes = "Notes 😀\\tkept.\\nSecond note.";
        String body = withRawField(
                withRawField(FULL_BODY, "narrative", "\"" + narrative + "\""),
                "handlingNotes",
                "\"" + handlingNotes + "\"");

        ApiResponse created = request("POST", "/api/cases", body);

        assertThat(created.status()).isEqualTo(201);
        JsonNode read = JSON.readTree(request("GET", created.location(), null).body());
        assertThat(read.get("narrative").asString())
                .isEqualTo("Line one 😀\twith tab.\nLine two 𝄞.");
        assertThat(read.get("analystNotes").get("handlingNotes").asString())
                .isEqualTo("Notes 😀\tkept.\nSecond note.");
    }

    @Test
    void supplementaryCharactersUseContractCodePointLimits() throws Exception {
        seedCfpb("A transfer is missing from my checking account.");
        String narrative = "transfer " + "\uD83D\uDE00".repeat(25_000);
        String body = FULL_BODY.replace(
                "My transfer is missing from my checking account.", narrative);

        ApiResponse response = request("POST", "/api/cases", body);

        assertThat(response.status()).isEqualTo(201);
        JsonNode created = JSON.readTree(response.body());
        assertThat(created.get("narrative").asString()).isEqualTo(narrative);
        assertThat(created.get("narrative").asString().codePointCount(
                0, created.get("narrative").asString().length())).isEqualTo(25_009);
    }

    /** Replaces one string-valued FULL_BODY property with a raw JSON literal. */
    private static String withRawField(String body, String field, String rawValue) {
        return body.replaceFirst(
                "(\"" + field + "\": )\"[^\"]*\"",
                "$1" + java.util.regex.Matcher.quoteReplacement(rawValue));
    }

    private String withReferences(UUID... references) {
        StringBuilder array = new StringBuilder("[");
        for (int index = 0; index < references.length; index++) {
            if (index > 0) {
                array.append(", ");
            }
            array.append('"').append(references[index]).append('"');
        }
        array.append(']');
        return FULL_BODY.replace("\"referenceCaseIds\": []",
                "\"referenceCaseIds\": " + array);
    }

    private UUID seedCfpb(String narrative) {
        try (TestDatabase database =
                TestDatabase.open(DATABASE_FILE, MigrationRunner.productionMigrations())) {
            UUID batchId = database.seedBatch(TestDatabase.sampleBatch());
            UUID id = UUID.randomUUID();
            Instant timestamp = Instant.parse("2026-10-26T10:00:00Z");
            database.caseRepository().insert(new CaseRecord(
                    id,
                    CaseRecord.Source.CFPB,
                    CHECKING,
                    narrative,
                    LocalDate.parse("2024-11-12"),
                    "Example Bank",
                    "Closed with explanation",
                    timestamp,
                    timestamp,
                    new CaseRecord.Provenance(
                            batchId,
                            "900000001",
                            "https://files.consumerfinance.gov/f/documents/synthetic-archive.zip",
                            "finresolve-sample.csv",
                            LocalDate.parse("2024-11-01"),
                            LocalDate.parse("2024-12-31"),
                            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"),
                    null,
                    List.of()));
            return id;
        }
    }

    private long caseCount() {
        Long value = jdbc.queryForObject("SELECT COUNT(*) FROM cases", Long.class);
        return value == null ? 0 : value;
    }

    private ApiResponse request(String method, String path, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + port + path));
        builder.header("Accept", "application/json");
        if (body != null) {
            builder.header("Content-Type", "application/json");
            builder.method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        }
        HttpResponse<String> response = client.send(
                builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return new ApiResponse(
                response.statusCode(),
                response.headers().firstValue("Location").orElse(null),
                response.headers().firstValue("Content-Type").orElse(""),
                response.body());
    }

    private static Path createDatabaseDirectory() {
        try {
            return Files.createTempDirectory("finresolve-retention-api");
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private record ApiResponse(int status, String location, String contentType, String body) {
    }
}
