package id.ac.finresolve.presentation;

import static org.assertj.core.api.Assertions.assertThat;

import id.ac.finresolve.domain.cases.CaseRecord;
import id.ac.finresolve.domain.category.CategoryValues;
import id.ac.finresolve.repository.db.MigrationRunner;
import id.ac.finresolve.domain.importing.BatchRecord;
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
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
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
class ReadApiTest {

    private static final Path DATABASE_DIRECTORY = createDatabaseDirectory();
    private static final Path DATABASE_FILE = DATABASE_DIRECTORY.resolve("finresolve.db");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String CSV_SHA =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

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
    void healthReturnsTheExactContractBody() throws Exception {
        ApiResponse response = get("/api/health");

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.contentType()).startsWith("application/json");
        assertThat(response.body()).isEqualTo("{\"status\":\"UP\",\"database\":\"UP\"}");
    }

    @Test
    void emptyCategoriesAndCasePageCarryContractMetadata() throws Exception {
        ApiResponse categories = get("/api/categories");
        assertThat(categories.status()).isEqualTo(200);
        JsonNode categoryBody = JSON.readTree(categories.body());
        assertThat(categoryBody.get("scope").asString()).isEqualTo("IMPORTED_CFPB_SAMPLE");
        assertThat(categoryBody.get("items").isArray()).isTrue();
        assertThat(categoryBody.get("items")).isEmpty();

        ApiResponse page = get("/api/cases");
        assertThat(page.status()).isEqualTo(200);
        JsonNode pageBody = JSON.readTree(page.body());
        assertThat(pageBody.get("items").isArray()).isTrue();
        assertThat(pageBody.get("items")).isEmpty();
        assertThat(pageBody.get("page").asInt()).isZero();
        assertThat(pageBody.get("size").asInt()).isEqualTo(20);
        assertThat(pageBody.get("totalElements").asInt()).isZero();
        assertThat(pageBody.get("totalPages").asInt()).isZero();
        assertThat(pageBody.get("hasNext").asBoolean()).isFalse();
    }

    @Test
    void categoriesSortNullSubcategoriesBeforeStrings() throws Exception {
        seedCfpbCases(
                new SeedCase(
                        "Checking or savings account",
                        null,
                        "Managing an account",
                        null,
                        "Synthetic narrative without subproduct."),
                new SeedCase(
                        "Checking or savings account",
                        "Checking account",
                        "Managing an account",
                        null,
                        "Synthetic narrative with subproduct."));

        ApiResponse response = get("/api/categories");

        assertThat(response.status()).isEqualTo(200);
        JsonNode items = JSON.readTree(response.body()).get("items");
        assertThat(items).hasSize(2);
        assertThat(items.get(0).get("subProduct").isNull()).isTrue();
        assertThat(items.get(1).get("subProduct").asString()).isEqualTo("Checking account");
        assertThat(items.get(0).has("subIssue")).isTrue();
        assertThat(items.get(0).get("subIssue").isNull()).isTrue();
    }

    @Test
    void cfpbDetailContainsProvenanceAndPresentNullAnalystNotes() throws Exception {
        UUID id = seedCfpbCases(new SeedCase(
                "Checking or savings account",
                "Checking account",
                "Managing an account",
                null,
                "A transfer is missing from my checking account.")).get(0);

        ApiResponse response = get("/api/cases/" + id);

        assertThat(response.status()).isEqualTo(200);
        String body = response.body();
        assertThat(sourceKeyCount(body)).isEqualTo(1);
        JsonNode detail = JSON.readTree(body);
        assertThat(detail.get("id").asString()).isEqualTo(id.toString());
        assertThat(detail.get("source").asString()).isEqualTo("CFPB");
        assertThat(detail.get("narrative").asString())
                .isEqualTo("A transfer is missing from my checking account.");
        assertThat(detail.get("receivedDate").asString()).isEqualTo("2024-11-12");
        assertThat(detail.get("createdAt").asString()).endsWith("Z");
        assertThat(detail.get("updatedAt").asString()).endsWith("Z");
        assertThat(detail.has("analystNotes")).isTrue();
        assertThat(detail.get("analystNotes").isNull()).isTrue();
        assertThat(detail.get("referenceCaseIds").isArray()).isTrue();
        assertThat(detail.get("referenceCaseIds")).isEmpty();
        assertThat(detail.has("provenance")).isTrue();
        JsonNode provenance = detail.get("provenance");
        assertThat(provenance.get("complaintId").asString()).isEqualTo("999999999");
        assertThat(provenance.get("archiveUrl").asString()).startsWith("https://");
        assertThat(provenance.get("sourceFileName").asString()).isEqualTo("finresolve-sample.csv");
        assertThat(provenance.get("periodStart").asString()).isEqualTo("2024-11-01");
        assertThat(provenance.get("periodEnd").asString()).isEqualTo("2024-12-31");
        assertThat(provenance.get("csvSha256").asString()).isEqualTo(CSV_SHA);
    }

    @Test
    void analystDetailHasNotesAndPresentNullProvenance() throws Exception {
        UUID id = UUID.randomUUID();
        Instant timestamp = Instant.parse("2026-10-26T10:00:00Z");
        try (TestDatabase database =
                TestDatabase.open(DATABASE_FILE, MigrationRunner.productionMigrations())) {
            database.caseRepository().insert(new CaseRecord(
                    id,
                    CaseRecord.Source.ANALYST,
                    new CategoryValues(
                            "Checking or savings account",
                            "Checking account",
                            "Managing an account",
                            null),
                    "An analyst-entered narrative.",
                    null,
                    null,
                    null,
                    timestamp,
                    timestamp,
                    null,
                    new CaseRecord.Notes(
                            "Reviewed the synthetic evidence.",
                            CaseRecord.Outcome.UNRESOLVED,
                            null),
                    List.of()));
        }

        ApiResponse response = get("/api/cases/" + id);

        assertThat(response.status()).isEqualTo(200);
        JsonNode detail = JSON.readTree(response.body());
        assertThat(detail.get("source").asString()).isEqualTo("ANALYST");
        assertThat(detail.has("provenance")).isTrue();
        assertThat(detail.get("provenance").isNull()).isTrue();
        assertThat(detail.get("analystNotes").get("handlingNotes").asString())
                .isEqualTo("Reviewed the synthetic evidence.");
        assertThat(detail.get("analystNotes").get("outcomeAssessment").asString())
                .isEqualTo("UNRESOLVED");
        assertThat(detail.get("analystNotes").has("evaluationNotes")).isTrue();
        assertThat(detail.get("analystNotes").get("evaluationNotes").isNull()).isTrue();
        assertThat(detail.get("referenceCaseIds")).isEmpty();
    }

    @Test
    void pageBeyondIntRangeReturnsEmptyItemsWithoutOffsetOverflow() throws Exception {
        seedCfpbCases(new SeedCase(
                "Checking or savings account",
                "Checking account",
                "Managing an account",
                null,
                "Synthetic narrative for a huge page."));

        ApiResponse response = get("/api/cases?page=2147483647&size=20");

        assertThat(response.status()).isEqualTo(200);
        JsonNode body = JSON.readTree(response.body());
        assertThat(body.get("items")).isEmpty();
        assertThat(body.get("page").asInt()).isEqualTo(Integer.MAX_VALUE);
        assertThat(body.get("size").asInt()).isEqualTo(20);
        assertThat(body.get("totalElements").asInt()).isEqualTo(1);
        assertThat(body.get("totalPages").asInt()).isEqualTo(1);
        assertThat(body.get("hasNext").asBoolean()).isFalse();
    }

    @Test
    void theContractServerPathIsMappedExactlyOnce() throws Exception {
        assertThat(get("/api/health").status()).isEqualTo(200);

        ApiResponse duplicated = get("/api/api/health");

        assertThat(duplicated.status()).isEqualTo(404);
        assertThat(JSON.readTree(duplicated.body()).get("code").asString())
                .isEqualTo("ROUTE_NOT_FOUND");
    }

    private List<UUID> seedCfpbCases(SeedCase... seeds) {
        try (TestDatabase database =
                TestDatabase.open(DATABASE_FILE, MigrationRunner.productionMigrations())) {
            BatchRecord batch = TestDatabase.sampleBatch();
            UUID batchId = database.seedBatch(batch);
            List<UUID> ids = new java.util.ArrayList<>();
            Instant base = Instant.parse("2026-10-26T10:00:00Z");
            for (int index = 0; index < seeds.length; index++) {
                SeedCase seed = seeds[index];
                Instant timestamp = base.plusSeconds(index);
                UUID id = UUID.randomUUID();
                database.caseRepository().insert(new CaseRecord(
                        id,
                        CaseRecord.Source.CFPB,
                        new CategoryValues(seed.product(), seed.subProduct(), seed.issue(), seed.subIssue()),
                        seed.narrative(),
                        LocalDate.parse("2024-11-12"),
                        "Example Bank (synthetic)",
                        "Closed with explanation",
                        timestamp,
                        timestamp,
                        new CaseRecord.Provenance(
                                batchId,
                                String.valueOf(999_999_999L + index),
                                "https://files.consumerfinance.gov/f/documents/synthetic-archive.zip",
                                "finresolve-sample.csv",
                                LocalDate.parse("2024-11-01"),
                                LocalDate.parse("2024-12-31"),
                                CSV_SHA),
                        null,
                        List.of()));
                ids.add(id);
            }
            return ids;
        }
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
        return new ApiResponse(
                response.statusCode(),
                response.headers().firstValue("Content-Type").orElse(""),
                response.body());
    }

    private static long sourceKeyCount(String serializedJson) {
        return Pattern.compile("\"source\"\\s*:").matcher(serializedJson).results().count();
    }

    private static Path createDatabaseDirectory() {
        try {
            return Files.createTempDirectory("finresolve-read-api");
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private record SeedCase(
            String product, String subProduct, String issue, String subIssue, String narrative) {
    }

    private record ApiResponse(int status, String contentType, String body) {
    }
}
