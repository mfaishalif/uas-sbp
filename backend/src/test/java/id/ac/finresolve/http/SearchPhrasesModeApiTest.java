package id.ac.finresolve.http;

import static org.assertj.core.api.Assertions.assertThat;

import id.ac.finresolve.cases.CaseRecord;
import id.ac.finresolve.categories.CategoryValues;
import id.ac.finresolve.db.MigrationRunner;
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
            "finresolve.similarity.mode=phrases",
            "finresolve.similarity.category-weight=0.4",
            "finresolve.similarity.text-weight=0.6",
            "finresolve.similarity.product-weight=0.35",
            "finresolve.similarity.sub-product-weight=0.15",
            "finresolve.similarity.issue-weight=0.35",
            "finresolve.similarity.sub-issue-weight=0.15",
            "finresolve.similarity.threshold=0.50"
        })
class SearchPhrasesModeApiTest {

    private static final Path DATABASE_DIRECTORY = createDatabaseDirectory();
    private static final Path DATABASE_FILE = DATABASE_DIRECTORY.resolve("finresolve.db");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final CategoryValues CATEGORIES = new CategoryValues(
            "Checking or savings account", "Checking account",
            "Managing an account", "Deposits and withdrawals");

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
        seedCfpb();
    }

    @Test
    void responseCarriesThePhrasesVersionAndCanonicalSharedTerms() throws Exception {
        HttpResponse<String> response = search("""
                {
                  "product": "Checking or savings account",
                  "subProduct": "Checking account",
                  "issue": "Managing an account",
                  "subIssue": "Deposits and withdrawals",
                  "narrative": "A cash machine charged double with a withheld balance.",
                  "limit": 5
                }
                """);

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = JSON.readTree(response.body());
        assertThat(body.get("status").asString()).isEqualTo("MATCHES");
        assertThat(body.get("configuration").get("algorithmVersion").asString())
                .isEqualTo("tfidf-phrases-v2");
        JsonNode match = body.get("results").get(0);
        assertThat(match.get("case").get("provenance").get("complaintId").asString())
                .isEqualTo("900000001");
        assertThat(match.get("sharedTerms"))
                .extracting(term -> term.get("term").asString())
                .contains("atm", "duplicatecharge", "heldfunds");
        double contributionSum = 0.0;
        for (JsonNode term : match.get("sharedTerms")) {
            contributionSum += term.get("contribution").asDouble();
        }
        assertThat(contributionSum)
                .isCloseTo(match.get("textScore").asDouble(),
                        org.assertj.core.data.Offset.offset(1e-12));
    }

    private void seedCfpb() {
        try (TestDatabase database =
                TestDatabase.open(DATABASE_FILE, MigrationRunner.productionMigrations())) {
            UUID batchId = database.seedBatch(TestDatabase.sampleBatch());
            Instant timestamp = Instant.parse("2026-10-26T10:00:00Z");
            database.caseRepository().insert(new CaseRecord(
                    UUID.randomUUID(),
                    CaseRecord.Source.CFPB,
                    CATEGORIES,
                    "The ATM charged twice and the funds held were released.",
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
        }
    }

    private HttpResponse<String> search(String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + "/api/search"))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        return client.send(
                request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static Path createDatabaseDirectory() {
        try {
            return Files.createTempDirectory("finresolve-search-phrases-api");
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }
}
