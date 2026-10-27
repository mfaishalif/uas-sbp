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

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "finresolve.similarity.category-weight=0.7",
            "finresolve.similarity.text-weight=0.3",
            "finresolve.similarity.product-weight=0.4",
            "finresolve.similarity.sub-product-weight=0.1",
            "finresolve.similarity.issue-weight=0.4",
            "finresolve.similarity.sub-issue-weight=0.1",
            "finresolve.similarity.threshold=0.0",
            "finresolve.similarity.default-limit=3",
            "finresolve.similarity.max-limit=7"
        })
class SearchConfigurationApiTest {

    private static final Path DATABASE_DIRECTORY = createDatabaseDirectory();
    private static final Path DATABASE_FILE = DATABASE_DIRECTORY.resolve("finresolve.db");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final CategoryValues CHECKING = new CategoryValues(
            "Checking or savings account", "Checking account", "Managing an account", null);

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
    void responseCarriesTheActualNondefaultConfiguration() throws Exception {
        seedCfpb();

        ApiResponse response = search("""
                {
                  "product": "Checking or savings account",
                  "subProduct": "Checking account",
                  "issue": "Managing an account",
                  "narrative": "My transfer is missing from my checking account."
                }
                """);

        assertThat(response.status()).isEqualTo(200);
        JsonNode body = JSON.readTree(response.body());
        assertThat(body.get("limit").asInt()).isEqualTo(3);
        JsonNode configuration = body.get("configuration");
        assertThat(configuration.get("categoryWeight").asDouble()).isEqualTo(0.7);
        assertThat(configuration.get("textWeight").asDouble()).isEqualTo(0.3);
        assertThat(configuration.get("categoryFieldWeights").get("product").asDouble())
                .isEqualTo(0.4);
        assertThat(configuration.get("categoryFieldWeights").get("subProduct").asDouble())
                .isEqualTo(0.1);
        assertThat(configuration.get("categoryFieldWeights").get("issue").asDouble())
                .isEqualTo(0.4);
        assertThat(configuration.get("categoryFieldWeights").get("subIssue").asDouble())
                .isEqualTo(0.1);
        assertThat(configuration.get("threshold").asDouble()).isZero();
        assertThat(configuration.get("requiresTextOverlap").asBoolean()).isTrue();
        assertThat(configuration.get("algorithmVersion").asString())
                .isEqualTo("tfidf-cosine-v1");

        ApiResponse aboveConfiguredMaximum = search("""
                {
                  "product": "Checking or savings account",
                  "issue": "Managing an account",
                  "narrative": "My transfer is missing from my checking account.",
                  "limit": 8
                }
                """);
        assertThat(aboveConfiguredMaximum.status()).isEqualTo(400);

        ApiResponse explicitLimit = search("""
                {
                  "product": "Checking or savings account",
                  "issue": "Managing an account",
                  "narrative": "My transfer is missing from my checking account.",
                  "limit": 5
                }
                """);
        assertThat(explicitLimit.status()).isEqualTo(200);
        assertThat(JSON.readTree(explicitLimit.body()).get("limit").asInt()).isEqualTo(5);
    }

    private UUID seedCfpb() {
        try (TestDatabase database =
                TestDatabase.open(DATABASE_FILE, MigrationRunner.productionMigrations())) {
            UUID batchId = database.seedBatch(TestDatabase.sampleBatch());
            UUID id = UUID.randomUUID();
            Instant timestamp = Instant.parse("2026-10-26T10:00:00Z");
            database.caseRepository().insert(new CaseRecord(
                    id,
                    CaseRecord.Source.CFPB,
                    CHECKING,
                    "A transfer is missing from my checking account.",
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

    private ApiResponse search(String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + "/api/search"))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = client.send(
                request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return new ApiResponse(response.statusCode(), response.body());
    }

    private static Path createDatabaseDirectory() {
        try {
            return Files.createTempDirectory("finresolve-search-config-api");
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private record ApiResponse(int status, String body) {
    }
}
