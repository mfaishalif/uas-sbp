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
class SearchApiTest {

    private static final Path DATABASE_DIRECTORY = createDatabaseDirectory();
    private static final Path DATABASE_FILE = DATABASE_DIRECTORY.resolve("finresolve.db");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final CategoryValues CHECKING = new CategoryValues(
            "Checking or savings account", "Checking account", "Managing an account", null);
    private static final String WARNING =
            "Kemiripan dan respons historis bukan bukti keberhasilan penanganan. "
                    + "Kalibrasi eksploratif dan belum divalidasi manusia.";

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
    private long nextComplaintId = 900_000_001L;
    private UUID batchId;

    @BeforeEach
    void resetDatabase() {
        jdbc.update("DELETE FROM case_references");
        jdbc.update("DELETE FROM analyst_notes");
        jdbc.update("DELETE FROM cases");
        jdbc.update("DELETE FROM import_batches");
    }

    @Test
    void acceptsAnIndonesianHeldFundsQuestionAsASearch() throws Exception {
        seedCfpb("A transfer was deducted from my checking account but never arrived.");

        ApiResponse response = search(query(
                "Checking or savings account",
                "Checking account",
                "Managing an account",
                "Bagaimana mengambil uang saya yang ditahan bank?",
                null));

        assertThat(response.status()).isEqualTo(200);
        JsonNode body = JSON.readTree(response.body());
        assertThat(body.get("status").asString())
                .isIn("MATCHES", "NO_RELEVANT_CASES");
        assertThat(body.get("results").isArray()).isTrue();
    }

    @Test
    void emptyCorpusReturnsSuccessWithoutCategoryMembership() throws Exception {
        ApiResponse response = search(query(
                "Unknown product",
                null,
                "Unknown issue",
                "A missing transfer narrative.",
                null));

        assertThat(response.status()).isEqualTo(200);
        JsonNode body = JSON.readTree(response.body());
        assertThat(body.get("status").asString()).isEqualTo("EMPTY_KNOWLEDGE_BASE");
        assertThat(body.get("message").asString())
                .isEqualTo("Basis kasus masih kosong; impor sampel CFPB terlebih dahulu.");
        assertThat(body.get("results")).isEmpty();
        assertThat(body.get("returnedCount").asInt()).isZero();
        assertThat(body.get("corpusSize").asInt()).isZero();
        assertThat(body.get("limit").asInt()).isEqualTo(5);
        assertThat(body.get("warnings")).hasSize(1);
        assertThat(body.get("warnings").get(0).asString()).isEqualTo(WARNING);
        assertDefaultConfiguration(body.get("configuration"));
    }

    @Test
    void meaninglessNarrativeIs400EvenWithAnEmptyCorpus() throws Exception {
        ApiResponse response = search(query(
                "Unknown product",
                null,
                "Unknown issue",
                "and the of",
                null));

        assertThat(response.status()).isEqualTo(400);
        JsonNode body = JSON.readTree(response.body());
        assertThat(body.get("code").asString()).isEqualTo("VALIDATION_ERROR");
        assertThat(body.get("fieldErrors").toString())
                .contains("\"field\":\"narrative\"")
                .contains("\"code\":\"NO_MEANINGFUL_TOKENS\"");
    }

    @Test
    void outOfVocabularyTextReturnsNoRelevantCases() throws Exception {
        seedCfpb("A transfer is missing from my checking account.");

        ApiResponse response = search(query(
                CHECKING.product(),
                CHECKING.subProduct(),
                CHECKING.issue(),
                "zebra quantum volcano",
                null));

        assertThat(response.status()).isEqualTo(200);
        JsonNode body = JSON.readTree(response.body());
        assertThat(body.get("status").asString()).isEqualTo("NO_RELEVANT_CASES");
        assertThat(body.get("message").asString())
                .isEqualTo("Tidak ada preseden yang cukup relevan; penilaian tetap dilakukan analis.");
        assertThat(body.get("results")).isEmpty();
        assertThat(body.get("returnedCount").asInt()).isZero();
        assertThat(body.get("corpusSize").asInt()).isEqualTo(1);
    }

    @Test
    void matchingQueryReturnsExplainableResultsAndFreshSnapshots() throws Exception {
        UUID firstId = seedCfpb("A transfer is missing from my checking account.");

        ApiResponse response = search(query(
                CHECKING.product(),
                CHECKING.subProduct(),
                CHECKING.issue(),
                "My transfer is missing from my checking account.",
                null));

        assertThat(response.status()).isEqualTo(200);
        JsonNode body = JSON.readTree(response.body());
        assertThat(body.get("status").asString()).isEqualTo("MATCHES");
        assertThat(body.get("message").asString())
                .isEqualTo("Ditemukan preseden yang memenuhi ambang awal.");
        assertThat(body.get("returnedCount").asInt()).isEqualTo(body.get("results").size());
        assertThat(body.get("corpusSize").asInt()).isEqualTo(1);
        assertThat(body.get("limit").asInt()).isEqualTo(5);
        JsonNode match = body.get("results").get(0);
        assertThat(match.get("case").get("id").asString()).isEqualTo(firstId.toString());
        assertThat(match.get("case").get("source").asString()).isEqualTo("CFPB");
        assertThat(match.get("case").get("provenance").get("complaintId").asString())
                .isNotBlank();
        assertThat(match.get("score").asDouble()).isBetween(0.0, 1.0);
        assertThat(match.get("categoryScore").asDouble()).isEqualTo(1.0);
        assertThat(match.get("textScore").asDouble()).isGreaterThan(0.0);
        assertThat(match.get("categoryExplanation")).hasSize(4);
        assertThat(match.get("categoryExplanation"))
                .extracting(explanation -> explanation.get("field").asString())
                .containsExactly("product", "subProduct", "issue", "subIssue");
        assertThat(match.get("sharedTerms").size()).isBetween(1, 10);

        UUID secondId = seedCfpb("Another transfer is missing from my checking account.");
        ApiResponse next = search(query(
                CHECKING.product(),
                CHECKING.subProduct(),
                CHECKING.issue(),
                "Another transfer is missing from my checking account.",
                null));

        JsonNode nextBody = JSON.readTree(next.body());
        assertThat(nextBody.get("corpusSize").asInt()).isEqualTo(2);
        assertThat(nextBody.get("results"))
                .extracting(result -> result.get("case").get("id").asString())
                .contains(firstId.toString(), secondId.toString());
    }

    @Test
    void committedRetentionAndUpdateAreVisibleOnTheNextSearch() throws Exception {
        seedCfpb("A transfer is missing from my checking account.");
        ApiResponse first = search(query(
                CHECKING.product(),
                CHECKING.subProduct(),
                CHECKING.issue(),
                "My transfer is missing from my checking account.",
                null));
        assertThat(JSON.readTree(first.body()).get("corpusSize").asInt()).isEqualTo(1);

        UUID analystId = seedAnalyst("A repeated transfer is missing from my checking account.");
        ApiResponse afterRetain = search(query(
                CHECKING.product(),
                CHECKING.subProduct(),
                CHECKING.issue(),
                "A repeated transfer is missing from my checking account.",
                null));
        JsonNode retainedBody = JSON.readTree(afterRetain.body());
        assertThat(retainedBody.get("corpusSize").asInt()).isEqualTo(2);
        JsonNode analystMatch = resultById(retainedBody, analystId);
        assertThat(analystMatch).isNotNull();
        assertThat(analystMatch.get("case").get("source").asString()).isEqualTo("ANALYST");
        assertThat(analystMatch.get("case").get("analystNotes").get("handlingNotes").asString())
                .isEqualTo("Reviewed the retained evidence.");
        assertThat(analystMatch.get("case").get("provenance").isNull()).isTrue();

        replaceAnalystNarrative(analystId, "zebra quantum volcano");
        ApiResponse afterUpdate = search(query(
                CHECKING.product(),
                CHECKING.subProduct(),
                CHECKING.issue(),
                "A repeated transfer is missing from my checking account.",
                null));
        JsonNode updatedBody = JSON.readTree(afterUpdate.body());
        assertThat(updatedBody.get("corpusSize").asInt()).isEqualTo(2);
        assertThat(resultById(updatedBody, analystId)).isNull();
    }

    @Test
    void limitIsValidatedAndOmittedLimitUsesTheConfiguredDefault() throws Exception {
        seedCfpb("A transfer is missing from my checking account.");

        for (String invalid : List.of("0", "21", "null")) {
            ApiResponse response = search(query(
                    CHECKING.product(),
                    CHECKING.subProduct(),
                    CHECKING.issue(),
                    "My transfer is missing from my checking account.",
                    invalid));
            assertThat(response.status()).as("limit %s", invalid).isEqualTo(400);
            assertThat(JSON.readTree(response.body()).get("code").asString())
                    .isIn("VALIDATION_ERROR", "MALFORMED_REQUEST");
        }

        ApiResponse one = search(query(
                CHECKING.product(),
                CHECKING.subProduct(),
                CHECKING.issue(),
                "My transfer is missing from my checking account.",
                "1"));
        assertThat(one.status()).isEqualTo(200);
        JsonNode oneBody = JSON.readTree(one.body());
        assertThat(oneBody.get("limit").asInt()).isEqualTo(1);
        assertThat(oneBody.get("returnedCount").asInt()).isLessThanOrEqualTo(1);

        ApiResponse decimal = search(query(
                CHECKING.product(),
                CHECKING.subProduct(),
                CHECKING.issue(),
                "My transfer is missing from my checking account.",
                "1.0"));
        assertThat(decimal.status()).isEqualTo(200);
        assertThat(JSON.readTree(decimal.body()).get("limit").asInt()).isEqualTo(1);
    }

    private static void assertDefaultConfiguration(JsonNode configuration) {
        assertThat(configuration.get("categoryWeight").asDouble()).isEqualTo(0.4);
        assertThat(configuration.get("textWeight").asDouble()).isEqualTo(0.6);
        JsonNode fields = configuration.get("categoryFieldWeights");
        assertThat(fields.get("product").asDouble()).isEqualTo(0.35);
        assertThat(fields.get("subProduct").asDouble()).isEqualTo(0.15);
        assertThat(fields.get("issue").asDouble()).isEqualTo(0.35);
        assertThat(fields.get("subIssue").asDouble()).isEqualTo(0.15);
        assertThat(configuration.get("threshold").asDouble()).isEqualTo(0.5);
        assertThat(configuration.get("requiresTextOverlap").asBoolean()).isTrue();
        assertThat(configuration.get("algorithmVersion").asString())
                .isEqualTo("tfidf-cosine-v1");
    }

    private String query(
            String product,
            String subProduct,
            String issue,
            String narrative,
            String limitJson) {
        StringBuilder json = new StringBuilder()
                .append("{\"product\":\"").append(product).append("\",")
                .append("\"issue\":\"").append(issue).append("\",")
                .append("\"narrative\":\"").append(narrative).append("\"");
        if (subProduct != null) {
            json.append(",\"subProduct\":\"").append(subProduct).append("\"");
        }
        if (limitJson != null) {
            json.append(",\"limit\":").append(limitJson);
        }
        return json.append('}').toString();
    }

    private UUID seedAnalyst(String narrative) {
        try (TestDatabase database =
                TestDatabase.open(DATABASE_FILE, MigrationRunner.productionMigrations())) {
            UUID id = UUID.randomUUID();
            Instant timestamp = Instant.parse("2026-10-26T11:00:00Z");
            database.caseRepository().insert(new CaseRecord(
                    id,
                    CaseRecord.Source.ANALYST,
                    CHECKING,
                    narrative,
                    null,
                    null,
                    null,
                    timestamp,
                    timestamp,
                    null,
                    new CaseRecord.Notes(
                            "Reviewed the retained evidence.",
                            CaseRecord.Outcome.PARTIALLY_RESOLVED,
                            null),
                    List.of()));
            return id;
        }
    }

    private void replaceAnalystNarrative(UUID id, String narrative) {
        try (TestDatabase database =
                TestDatabase.open(DATABASE_FILE, MigrationRunner.productionMigrations())) {
            CaseRecord existing = database.caseRepository().find(id).orElseThrow();
            database.caseRepository().replaceAnalyst(new CaseRecord(
                    existing.id(),
                    CaseRecord.Source.ANALYST,
                    existing.categories(),
                    narrative,
                    existing.receivedDate(),
                    existing.company(),
                    existing.companyResponse(),
                    existing.createdAt(),
                    existing.updatedAt().plusSeconds(1),
                    null,
                    existing.notes(),
                    existing.referenceCaseIds()));
        }
    }

    private static JsonNode resultById(JsonNode body, UUID id) {
        for (JsonNode result : body.get("results")) {
            if (result.get("case").get("id").asString().equals(id.toString())) {
                return result;
            }
        }
        return null;
    }

    private UUID seedCfpb(String narrative) {        try (TestDatabase database =
                TestDatabase.open(DATABASE_FILE, MigrationRunner.productionMigrations())) {
            if (batchId == null) {
                batchId = database.seedBatch(TestDatabase.sampleBatch());
            }
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
                            String.valueOf(nextComplaintId++),
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

    private ApiResponse search(String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + "/api/search"))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = client.send(
                request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return new ApiResponse(
                response.statusCode(),
                response.headers().firstValue("Content-Type").orElse(""),
                response.body());
    }

    private static Path createDatabaseDirectory() {
        try {
            return Files.createTempDirectory("finresolve-search-api");
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private record ApiResponse(int status, String contentType, String body) {
    }
}
