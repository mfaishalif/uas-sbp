package id.ac.finresolve.presentation;

import static org.assertj.core.api.Assertions.assertThat;

import id.ac.finresolve.repository.db.Timestamps;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
class ImportApiTest {

    private static final Path DATABASE_DIRECTORY = createDatabaseDirectory();
    private static final Path DATABASE_FILE = DATABASE_DIRECTORY.resolve("finresolve.db");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String BOUNDARY = "----FinResolveImportBoundary";
    private static final String HEADER =
            "Complaint ID,Product,Sub-product,Issue,Sub-issue,"
                    + "Consumer complaint narrative,Company,Company response to consumer,Date received";
    private static final LocalDate PERIOD_START = LocalDate.parse("2024-11-01");
    private static final LocalDate PERIOD_END = LocalDate.parse("2024-12-31");
    private static final String ARCHIVE_URL =
            "https://files.consumerfinance.gov/f/documents/synthetic-archive.zip";

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("finresolve.db.path", DATABASE_FILE::toString);
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + DATABASE_FILE);
    }

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @BeforeEach
    void resetDatabase() {
        jdbc.update("DELETE FROM case_references");
        jdbc.update("DELETE FROM analyst_notes");
        jdbc.update("DELETE FROM cases");
        jdbc.update("DELETE FROM import_batches");
    }

    @Test
    void importsNewFileAs201ReplaysAs200AndExpandsCategories() throws Exception {
        byte[] csv = csvWithHeader(
                row("910000001", "Checking or savings account", "Checking account",
                        "Managing an account", null,
                        "A meaningful synthetic transfer narrative", "Example Bank", "Closed",
                        "2024-11-05"));

        ApiResponse first = importCsv(csv, metadataJson("sample.csv", ARCHIVE_URL,
                PERIOD_START, PERIOD_END));

        assertThat(first.status()).isEqualTo(201);
        JsonNode firstBody = JSON.readTree(first.body());
        assertThat(firstBody.get("replayed").asBoolean()).isFalse();
        assertThat(firstBody.get("batchId").asString()).hasSize(36);
        assertThat(firstBody.get("metadata").get("sourceFileName").asString())
                .isEqualTo("sample.csv");
        assertThat(firstBody.get("csvSha256").asString()).hasSize(64);
        assertThat(firstBody.get("processed").asInt()).isEqualTo(1);
        assertThat(firstBody.get("imported").asInt()).isEqualTo(1);
        assertThat(firstBody.get("duplicates").asInt()).isZero();
        assertThat(firstBody.get("rejected").asInt()).isZero();
        assertThat(firstBody.get("errors").isArray()).isTrue();
        assertThat(firstBody.get("errors")).isEmpty();
        assertThat(firstBody.get("errorsTruncated").asBoolean()).isFalse();
        assertThat(firstBody.get("importedAt").asString()).endsWith("Z");
        assertThat(caseCount()).isEqualTo(1);
        assertThat(batchCount()).isEqualTo(1);

        ApiResponse categories = get("/api/categories");
        assertThat(categories.status()).isEqualTo(200);
        JsonNode items = JSON.readTree(categories.body()).get("items");
        assertThat(items).hasSize(1);
        assertThat(items.get(0).get("product").asString())
                .isEqualTo("Checking or savings account");
        assertThat(items.get(0).get("subProduct").asString()).isEqualTo("Checking account");
        assertThat(items.get(0).get("issue").asString()).isEqualTo("Managing an account");

        ApiResponse replay = importCsv(csv, metadataJson(
                "  sample.csv  ", "  " + ARCHIVE_URL + "  ", PERIOD_START, PERIOD_END));

        assertThat(replay.status()).isEqualTo(200);
        JsonNode replayBody = JSON.readTree(replay.body());
        assertThat(replayBody.get("replayed").asBoolean()).isTrue();
        assertThat(replayBody.get("batchId").asString())
                .isEqualTo(firstBody.get("batchId").asString());
        assertThat(replayBody.get("importedAt").asString())
                .isEqualTo(firstBody.get("importedAt").asString());
        assertThat(caseCount()).isEqualTo(1);
        assertThat(batchCount()).isEqualTo(1);
    }

    @Test
    void allInvalidCsvIs201WithImportedZeroAndTwentySafeErrors() throws Exception {
        StringBuilder csv = new StringBuilder();
        for (int index = 1; index <= 21; index++) {
            csv.append(row(String.valueOf(920000000 + index), "Product A", null, "Issue A", null,
                    "", "Company A", "Closed", ""));
        }

        ApiResponse response = importCsv(csvWithHeader(csv.toString()), metadataJson(
                "all-invalid.csv", ARCHIVE_URL, PERIOD_START, PERIOD_END));

        assertThat(response.status()).isEqualTo(201);
        JsonNode body = JSON.readTree(response.body());
        assertThat(body.get("processed").asInt()).isEqualTo(21);
        assertThat(body.get("imported").asInt()).isZero();
        assertThat(body.get("duplicates").asInt()).isZero();
        assertThat(body.get("rejected").asInt()).isEqualTo(21);
        assertThat(body.get("errors")).hasSize(20);
        assertThat(body.get("errorsTruncated").asBoolean()).isTrue();
        assertThat(caseCount()).isZero();
        assertThat(batchCount()).isEqualTo(1);
    }

    @Test
    void rejectsMalformedFilesWithoutWritingAnything() throws Exception {
        assertFileRejected(csv(HEADER + "\n"), "INVALID_CSV");
        assertFileRejected(csv(HEADER.replace("Issue,", "") + "\n"
                + "930000001,Product A,Sub A,A meaningful narrative,Company A,Closed\n"),
                "INVALID_CSV");
        assertFileRejected(csv("Complaint ID,Complaint ID,Product,Issue,"
                        + "Consumer complaint narrative\n"
                        + "930000002,930000002,Product A,Issue A,A meaningful narrative\n"),
                "INVALID_CSV");
        assertFileRejected(csv(HEADER + "\n"
                        + "930000003,Product A,Sub A,Issue A,,\"unterminated,Company A,Closed,\n"),
                "INVALID_CSV");

        ByteArrayOutputStream invalidUtf8 = new ByteArrayOutputStream();
        invalidUtf8.write(csv(HEADER + "\n"));
        invalidUtf8.write(("930000004,Product A,Sub A,Issue A,,A meaningful narrative,"
                        + "Company A,Closed,").getBytes(StandardCharsets.UTF_8));
        invalidUtf8.write(new byte[] {(byte) 0xC3, (byte) 0x28});
        invalidUtf8.write('\n');
        assertFileRejected(invalidUtf8.toByteArray(), "INVALID_CSV");

        assertThat(caseCount()).isZero();
        assertThat(batchCount()).isZero();
    }

    @Test
    void rejectsFiveThousandOneRecordsBeforeWriting() throws Exception {
        StringBuilder csv = new StringBuilder(HEADER).append('\n');
        for (int index = 1; index <= 5_001; index++) {
            csv.append(row(String.valueOf(940000000 + index), "Product A", null, "Issue A", null,
                    "A meaningful narrative " + index, "Company A", "Closed", ""));
        }

        ApiResponse response = importCsv(csv(csv.toString()), metadataJson(
                "too-many.csv", ARCHIVE_URL, PERIOD_START, PERIOD_END));

        assertThat(response.status()).isEqualTo(400);
        assertThat(errorCode(response)).isEqualTo("IMPORT_LIMIT_EXCEEDED");
        assertThat(caseCount()).isZero();
        assertThat(batchCount()).isZero();
    }

    @Test
    void acceptsExactlyTwentyMebibytesAndRejectsOneByteMore() throws Exception {
        byte[] exact = twentyMebibyteCsv();
        assertThat(exact).hasSize(20 * 1024 * 1024);

        ApiResponse accepted = importCsv(exact, metadataJson(
                "twenty-mebibytes.csv", ARCHIVE_URL, PERIOD_START, PERIOD_END));

        assertThat(accepted.status()).isEqualTo(201);
        assertThat(JSON.readTree(accepted.body()).get("imported").asInt()).isEqualTo(1);
        assertThat(caseCount()).isEqualTo(1);
        assertThat(batchCount()).isEqualTo(1);

        byte[] oneByteMore = Arrays.copyOf(exact, exact.length + 1);
        ApiResponse rejected = importCsv(oneByteMore, metadataJson(
                "twenty-mebibytes-plus-one.csv", ARCHIVE_URL, PERIOD_START, PERIOD_END));

        assertThat(rejected.status()).isEqualTo(413);
        assertThat(errorCode(rejected)).isEqualTo("PAYLOAD_TOO_LARGE");
        assertThat(caseCount()).isEqualTo(1);
        assertThat(batchCount()).isEqualTo(1);
    }

    @Test
    void rejectsARequestLargerThanTwentyOneMebibytes() throws Exception {
        byte[] extraPart = new byte[2 * 1024 * 1024];
        byte[] body = multipart(
                metadataJson("request-limit.csv", ARCHIVE_URL, PERIOD_START, PERIOD_END),
                "application/json",
                twentyMebibyteCsv(),
                "text/csv",
                "request-limit.csv",
                extraPart,
                true,
                true);

        ApiResponse response = postMultipart(body);

        assertThat(response.status()).isEqualTo(413);
        assertThat(errorCode(response)).isEqualTo("PAYLOAD_TOO_LARGE");
        assertThat(caseCount()).isZero();
        assertThat(batchCount()).isZero();
    }

    @Test
    void rejectsMissingPartsInvalidMetadataAndWrongMetadataMediaType() throws Exception {
        byte[] csv = csvWithHeader(row("950000001", "Product A", null, "Issue A", null,
                "A meaningful narrative", "Company A", "Closed", ""));

        ApiResponse missingFile = postMultipart(multipart(
                metadataJson("sample.csv", ARCHIVE_URL, PERIOD_START, PERIOD_END),
                "application/json", null, null, null, null, true, false));
        assertThat(missingFile.status()).isEqualTo(400);

        ApiResponse missingMetadata = postMultipart(multipart(
                null, null, csv, "text/csv", "sample.csv", null, false, true));
        assertThat(missingMetadata.status()).isEqualTo(400);

        ApiResponse wrongMediaType = postMultipart(multipart(
                metadataJson("sample.csv", ARCHIVE_URL, PERIOD_START, PERIOD_END),
                "text/plain", csv, "text/csv", "sample.csv", null, true, true));
        assertThat(wrongMediaType.status()).isEqualTo(415);
        assertThat(errorCode(wrongMediaType)).isEqualTo("UNSUPPORTED_MEDIA_TYPE");

        ApiResponse malformedMetadata = postMultipart(multipart(
                "not-json", "application/json", csv, "text/csv", "sample.csv",
                null, true, true));
        assertThat(malformedMetadata.status()).isEqualTo(400);

        ApiResponse insecureUrl = postMultipart(multipart(
                metadataJson("sample.csv", "http://example.test/archive.zip",
                        PERIOD_START, PERIOD_END),
                "application/json", csv, "text/csv", "sample.csv", null, true, true));
        assertThat(insecureUrl.status()).isEqualTo(400);
        assertThat(errorCode(insecureUrl)).isEqualTo("VALIDATION_ERROR");

        ApiResponse reversedPeriod = postMultipart(multipart(
                metadataJson("sample.csv", ARCHIVE_URL, PERIOD_END, PERIOD_START),
                "application/json", csv, "text/csv", "sample.csv", null, true, true));
        assertThat(reversedPeriod.status()).isEqualTo(400);
        assertThat(errorCode(reversedPeriod)).isEqualTo("VALIDATION_ERROR");

        assertThat(caseCount()).isZero();
        assertThat(batchCount()).isZero();
    }

    @Test
    void rejectsMetadataJsonTypesThatJacksonWouldCoerce() throws Exception {
        byte[] csv = csvWithHeader(row("955000001", "Product A", null, "Issue A", null,
                "A meaningful narrative", "Company A", "Closed", ""));

        for (String[] scenario : List.of(
                new String[] {metadataWithRawValues("123", "\"2024-11-01\""), "sourceFileName"},
                new String[] {metadataWithRawValues("true", "\"2024-11-01\""), "sourceFileName"},
                new String[] {metadataWithRawValues("\"sample.csv\"", "[2024,11,1]"), "periodStart"})) {
            ApiResponse response = postMultipart(multipart(
                    scenario[0], "application/json", csv, "text/csv", "sample.csv",
                    null, true, true));

            assertThat(response.status()).as("metadata %s", scenario[0]).isEqualTo(400);
            assertThat(errorCode(response)).isEqualTo("VALIDATION_ERROR");
            assertThat(response.body())
                    .contains("\"field\":\"" + scenario[1] + "\"")
                    .contains("\"code\":\"INVALID_FORMAT\"");
        }

        assertThat(caseCount()).isZero();
        assertThat(batchCount()).isZero();
    }

    @Test
    void acceptsApplicationOctetStreamForTheFilePart() throws Exception {
        byte[] csv = csvWithHeader(row("960000001", "Product A", null, "Issue A", null,
                "A meaningful octet stream narrative", "Company A", "Closed", ""));

        ApiResponse response = postMultipart(multipart(
                metadataJson("octet.csv", ARCHIVE_URL, PERIOD_START, PERIOD_END),
                "application/json", csv, "application/octet-stream", "octet.csv",
                null, true, true));

        assertThat(response.status()).isEqualTo(201);
        assertThat(caseCount()).isEqualTo(1);
    }

    @Test
    void metadataArchiveUrlIsNeverFetched() throws Exception {
        byte[] csv = csvWithHeader(row("970000001", "Product A", null, "Issue A", null,
                "A narrative that must not trigger network access", "Company A", "Closed", ""));

        ApiResponse response = importCsv(csv, metadataJson(
                "no-network.csv", "https://192.0.2.1/never-fetched.zip",
                PERIOD_START, PERIOD_END));

        assertThat(response.status()).isEqualTo(201);
        assertThat(JSON.readTree(response.body()).get("imported").asInt()).isEqualTo(1);
    }

    @Test
    void concurrentImportsAtTheLastSlotAreAtomic() throws Exception {
        seedCases(4_999);

        byte[] twoRowsA = csvWithHeader(
                row("980000001", "Product A", null, "Issue A", null,
                        "First two-row narrative", "Company A", "Closed", ""),
                row("980000002", "Product A", null, "Issue A", null,
                        "Second two-row narrative", "Company A", "Closed", ""));
        byte[] twoRowsB = csvWithHeader(
                row("980000003", "Product A", null, "Issue A", null,
                        "Third two-row narrative", "Company A", "Closed", ""),
                row("980000004", "Product A", null, "Issue A", null,
                        "Fourth two-row narrative", "Company A", "Closed", ""));

        List<ApiResponse> twoRowResponses = runConcurrently(
                () -> importCsv(twoRowsA, metadataJson(
                        "two-rows-a.csv", ARCHIVE_URL, PERIOD_START, PERIOD_END)),
                () -> importCsv(twoRowsB, metadataJson(
                        "two-rows-b.csv", ARCHIVE_URL, PERIOD_START, PERIOD_END)));

        assertThat(twoRowResponses).extracting(ApiResponse::status)
                .containsExactlyInAnyOrder(409, 409);
        assertThat(twoRowResponses).allSatisfy(response ->
                assertThat(errorCode(response)).isEqualTo("KNOWLEDGE_BASE_LIMIT_EXCEEDED"));
        assertThat(caseCount()).isEqualTo(4_999);
        assertThat(batchCount()).isEqualTo(1);

        byte[] oneRowA = csvWithHeader(row("990000001", "Product A", null, "Issue A", null,
                "First one-row narrative", "Company A", "Closed", ""));
        byte[] oneRowB = csvWithHeader(row("990000002", "Product A", null, "Issue A", null,
                "Second one-row narrative", "Company A", "Closed", ""));

        List<ApiResponse> oneRowResponses = runConcurrently(
                () -> importCsv(oneRowA, metadataJson(
                        "one-row-a.csv", ARCHIVE_URL, PERIOD_START, PERIOD_END)),
                () -> importCsv(oneRowB, metadataJson(
                        "one-row-b.csv", ARCHIVE_URL, PERIOD_START, PERIOD_END)));

        assertThat(oneRowResponses).extracting(ApiResponse::status)
                .containsExactlyInAnyOrder(201, 409);
        assertThat(caseCount()).isEqualTo(5_000);
        assertThat(batchCount()).isEqualTo(2);
    }

    private List<ApiResponse> runConcurrently(
            Callable<ApiResponse> first, Callable<ApiResponse> second) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<ApiResponse> firstResult = pool.submit(() -> {
                ready.countDown();
                start.await();
                return first.call();
            });
            Future<ApiResponse> secondResult = pool.submit(() -> {
                ready.countDown();
                start.await();
                return second.call();
            });
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return List.of(
                    firstResult.get(120, TimeUnit.SECONDS),
                    secondResult.get(120, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }

    private void assertFileRejected(byte[] csv, String code) throws Exception {
        ApiResponse response = importCsv(csv, metadataJson(
                UUID.randomUUID() + ".csv", ARCHIVE_URL, PERIOD_START, PERIOD_END));
        assertThat(response.status()).isEqualTo(400);
        assertThat(errorCode(response)).isEqualTo(code);
    }

    private ApiResponse importCsv(byte[] csv, String metadataJson) throws Exception {
        return postMultipart(multipart(
                metadataJson, "application/json", csv, "text/csv", "sample.csv",
                null, true, true));
    }

    private ApiResponse get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<byte[]> response =
                client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        return new ApiResponse(response.statusCode(), response.body());
    }

    private ApiResponse postMultipart(byte[] body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + "/api/imports/cfpb"))
                .timeout(Duration.ofSeconds(120))
                .header("Accept", "application/json")
                .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        HttpResponse<byte[]> response =
                client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        return new ApiResponse(response.statusCode(), response.body());
    }

    private static byte[] multipart(
            String metadataJson,
            String metadataContentType,
            byte[] file,
            String fileContentType,
            String fileName,
            byte[] extraPart,
            boolean includeMetadata,
            boolean includeFile) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        if (includeMetadata) {
            body.writeBytes(("--" + BOUNDARY + "\r\n"
                    + "Content-Disposition: form-data; name=\"metadata\"\r\n"
                    + "Content-Type: " + metadataContentType + "\r\n\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            body.writeBytes(metadataJson.getBytes(StandardCharsets.UTF_8));
            body.writeBytes("\r\n".getBytes(StandardCharsets.UTF_8));
        }
        if (includeFile) {
            body.writeBytes(("--" + BOUNDARY + "\r\n"
                    + "Content-Disposition: form-data; name=\"file\"; filename=\""
                    + fileName + "\"\r\n"
                    + "Content-Type: " + fileContentType + "\r\n\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            body.writeBytes(file);
            body.writeBytes("\r\n".getBytes(StandardCharsets.UTF_8));
        }
        if (extraPart != null) {
            body.writeBytes(("--" + BOUNDARY + "\r\n"
                    + "Content-Disposition: form-data; name=\"extra\"\r\n\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            body.writeBytes(extraPart);
            body.writeBytes("\r\n".getBytes(StandardCharsets.UTF_8));
        }
        body.writeBytes(("--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return body.toByteArray();
    }

    private static byte[] twentyMebibyteCsv() {
        byte[] prefix = ("Complaint ID,Product,Issue,Consumer complaint narrative,Tags\n"
                        + "991000001,Product A,Issue A,\"A meaningful padded narrative\",")
                .getBytes(StandardCharsets.UTF_8);
        byte[] suffix = "\n".getBytes(StandardCharsets.UTF_8);
        int size = 20 * 1024 * 1024;
        byte[] file = new byte[size];
        System.arraycopy(prefix, 0, file, 0, prefix.length);
        Arrays.fill(file, prefix.length, size - suffix.length, (byte) 'x');
        System.arraycopy(suffix, 0, file, size - suffix.length, suffix.length);
        return file;
    }

    private static byte[] csv(String... rows) {
        return String.join("", rows).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] csvWithHeader(String... rows) {
        return csv(HEADER + "\n" + String.join("", rows));
    }

    private static String row(
            String id,
            String product,
            String subProduct,
            String issue,
            String subIssue,
            String narrative,
            String company,
            String response,
            String date) {
        return String.join(",",
                id,
                product,
                subProduct == null ? "" : subProduct,
                issue,
                subIssue == null ? "" : subIssue,
                quote(narrative),
                company,
                response,
                date) + "\n";
    }

    private static String quote(String value) {
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private static String metadataJson(
            String sourceFileName, String archiveUrl, LocalDate start, LocalDate end) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("archiveUrl", archiveUrl);
        metadata.put("sourceFileName", sourceFileName);
        metadata.put("periodStart", start.toString());
        metadata.put("periodEnd", end.toString());
        return JSON.writeValueAsString(metadata);
    }

    /** Builds metadata where two values are raw JSON literals rather than JSON strings. */
    private static String metadataWithRawValues(String sourceFileName, String periodStart) {
        return "{\"archiveUrl\":\"" + ARCHIVE_URL + "\""
                + ",\"sourceFileName\":" + sourceFileName
                + ",\"periodStart\":" + periodStart
                + ",\"periodEnd\":\"" + PERIOD_END + "\"}";
    }

    private static String errorCode(ApiResponse response) {
        return JSON.readTree(response.body()).get("code").asString();
    }

    private long caseCount() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM cases", Long.class);
        return count == null ? 0 : count;
    }

    private long batchCount() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM import_batches", Long.class);
        return count == null ? 0 : count;
    }

    private void seedCases(int count) {
        UUID batchId = UUID.randomUUID();
        String timestamp = Timestamps.format(Instant.parse("2026-10-26T10:00:00Z"));
        jdbc.update(
                """
                INSERT INTO import_batches (
                    id, archive_url, source_file_name, period_start, period_end, csv_sha256,
                    imported_at, processed, imported, duplicates, rejected, errors_json
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0, 0, '[]')
                """,
                batchId.toString(),
                "https://seed.test/archive.zip",
                "seed.csv",
                PERIOD_START.toString(),
                PERIOD_END.toString(),
                "f".repeat(64),
                timestamp,
                count,
                count);

        List<Object[]> rows = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            rows.add(new Object[] {
                UUID.randomUUID().toString(),
                "CFPB",
                String.valueOf(700_000_000L + index),
                batchId.toString(),
                "2024-11-12",
                "Seed product",
                "Seed issue",
                "Seed narrative " + index,
                timestamp,
                timestamp
            });
        }
        jdbc.batchUpdate(
                """
                INSERT INTO cases (
                    id, source, complaint_id, batch_id, received_date, product,
                    issue, narrative, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                rows);
    }

    private static Path createDatabaseDirectory() {
        try {
            return Files.createTempDirectory("finresolve-import-api");
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private record ApiResponse(int status, byte[] rawBody) {

        String body() {
            return new String(rawBody, StandardCharsets.UTF_8);
        }
    }
}
