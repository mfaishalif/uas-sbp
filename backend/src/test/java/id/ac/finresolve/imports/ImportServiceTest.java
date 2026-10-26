package id.ac.finresolve.imports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import id.ac.finresolve.cases.CaseRecord;
import id.ac.finresolve.categories.CategoryRepository;
import id.ac.finresolve.categories.CategoryValues;
import id.ac.finresolve.db.Timestamps;
import id.ac.finresolve.http.BusinessException;
import id.ac.finresolve.support.TestDatabase;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ImportServiceTest {

    private static final String HEADER =
            "Complaint ID,Product,Sub-product,Issue,Sub-issue,"
                    + "Consumer complaint narrative,Company,Company response to consumer,Date received";
    private static final Instant NOW = Instant.parse("2026-10-26T10:00:00Z");
    private static final LocalDate PERIOD_START = LocalDate.parse("2024-11-01");
    private static final LocalDate PERIOD_END = LocalDate.parse("2024-12-31");

    @TempDir
    Path directory;

    private TestDatabase database;
    private ImportRepository imports;
    private ImportService service;
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final AtomicInteger idSequence = new AtomicInteger();

    @BeforeEach
    void setUp() {
        database = TestDatabase.create(directory);
        imports = new ImportRepository(database.database());
        Supplier<UUID> ids = () -> UUID.nameUUIDFromBytes(
                ("task5-case-" + idSequence.getAndIncrement())
                        .getBytes(StandardCharsets.UTF_8));
        service = new ImportService(
                imports, database.caseRepository(), database.database(), clock, ids);
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    @Test
    void importsValidRowsAndClassifiesDuplicatesAndRejections() {
        String csv = HEADER + "\n"
                + row("100", "Product A", "Sub A", "Issue A", "Sub issue A",
                "First valid narrative", "Company A", "Closed", "2024-11-05")
                + row("100", "Product A", "Sub A", "Issue A", "Sub issue A",
                "Second duplicate narrative", "Company A", "Closed", "2024-11-05")
                + row("101", "Product A", "Sub A", "Issue A", "Sub issue A",
                "", "Company A", "Closed", "2024-11-05")
                + row("102", "Product A", "Sub A", "Issue A", "Sub issue A",
                "  Padded narrative\nwith a newline  ", "Company A", "Closed", "2024-11-06");

        ImportService.Result result = importCsv(csv);
        BatchRecord batch = result.batch();

        assertThat(result.replayed()).isFalse();
        assertThat(batch.processed()).isEqualTo(4);
        assertThat(batch.imported()).isEqualTo(2);
        assertThat(batch.duplicates()).isEqualTo(1);
        assertThat(batch.rejected()).isEqualTo(1);
        assertThat(batch.processed())
                .isEqualTo(batch.imported() + batch.duplicates() + batch.rejected());
        assertThat(batch.errors()).hasSize(1);
        assertThat(batch.errors().get(0).recordNumber()).isEqualTo(3);
        assertThat(batch.errors().get(0).code()).isEqualTo("REQUIRED");
        assertThat(batch.importedAt()).isEqualTo(NOW);
        assertThat(database.caseRepository().count()).isEqualTo(2);

        CaseRecord first = caseWithComplaintId("100");
        assertThat(first.narrative()).isEqualTo("First valid narrative");
        assertThat(first.provenance().batchId()).isEqualTo(batch.id());
        assertThat(first.provenance().archiveUrl()).isEqualTo(metadata().archiveUrl());
        assertThat(first.provenance().sourceFileName()).isEqualTo(metadata().sourceFileName());
        assertThat(first.provenance().periodStart()).isEqualTo(PERIOD_START);
        assertThat(first.provenance().periodEnd()).isEqualTo(PERIOD_END);
        assertThat(first.provenance().csvSha256()).hasSize(64);

        CaseRecord padded = caseWithComplaintId("102");
        assertThat(padded.narrative()).isEqualTo("  Padded narrative\nwith a newline  ");
    }

    @Test
    void retainsTheFirstValidDuplicateAndRejectsInvalidDuplicatesBeforeDedupe() {
        String csv = HEADER + "\n"
                + row("200", "Product A", null, "Issue A", null,
                "", "Company A", "Closed", "")
                + row("200", "Product A", null, "Issue A", null,
                "First valid narrative", "Company A", "Closed", "")
                + row("200", "Product A", null, "Issue A", null,
                "Second valid narrative", "Company A", "Closed", "");

        ImportService.Result result = importCsv(csv);

        assertThat(result.batch().imported()).isEqualTo(1);
        assertThat(result.batch().duplicates()).isEqualTo(1);
        assertThat(result.batch().rejected()).isEqualTo(1);
        assertThat(database.caseRepository().count()).isEqualTo(1);
        assertThat(caseWithComplaintId("200").narrative()).isEqualTo("First valid narrative");
    }

    @Test
    void replaysIdenticalBytesAndTrimmedIdenticalMetadataWithoutWriting() {
        String csv = HEADER + "\n"
                + row("300", "Product A", null, "Issue A", null,
                "A replayable narrative", "Company A", "Closed", "2024-11-05");
        ImportService.Result first = importCsv(csv);
        long casesBefore = database.caseRepository().count();
        long batchesBefore = batchCount();

        ImportMetadataValue paddedMetadata = new ImportMetadataValue(
                "  " + metadata().archiveUrl() + "  ",
                "  " + metadata().sourceFileName() + "  ",
                PERIOD_START,
                PERIOD_END);
        ImportService.Result replay = service.importCsv(
                new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)), paddedMetadata);

        assertThat(replay.replayed()).isTrue();
        assertThat(replay.batch().id()).isEqualTo(first.batch().id());
        assertThat(replay.batch().importedAt()).isEqualTo(first.batch().importedAt());
        assertThat(replay.batch().processed()).isEqualTo(first.batch().processed());
        assertThat(replay.batch().errors()).isEqualTo(first.batch().errors());
        assertThat(database.caseRepository().count()).isEqualTo(casesBefore);
        assertThat(batchCount()).isEqualTo(batchesBefore);
    }

    @Test
    void changedMetadataCreatesANewDuplicateCountingBatch() {
        String csv = HEADER + "\n"
                + row("400", "Product A", null, "Issue A", null,
                "A duplicate-counted narrative", "Company A", "Closed", "2024-11-05");
        ImportService.Result first = importCsv(csv);

        ImportMetadataValue changed = new ImportMetadataValue(
                metadata().archiveUrl(),
                "different-source.csv",
                PERIOD_START,
                PERIOD_END);
        ImportService.Result second = service.importCsv(
                new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)), changed);

        assertThat(second.replayed()).isFalse();
        assertThat(second.batch().id()).isNotEqualTo(first.batch().id());
        assertThat(second.batch().imported()).isZero();
        assertThat(second.batch().duplicates()).isEqualTo(1);
        assertThat(second.batch().rejected()).isZero();
        assertThat(database.caseRepository().count()).isEqualTo(1);
        assertThat(batchCount()).isEqualTo(2);
    }

    @Test
    void allInvalidCsvCreatesABatchWithImportedZeroAndReplayRetainsTheReport() {
        StringBuilder csv = new StringBuilder(HEADER).append('\n');
        for (int index = 1; index <= 21; index++) {
            csv.append(row(String.valueOf(500 + index), "Product A", null, "Issue A", null,
                    "", "Company A", "Closed", ""));
        }

        ImportService.Result result = importCsv(csv.toString());
        BatchRecord batch = result.batch();

        assertThat(result.replayed()).isFalse();
        assertThat(batch.processed()).isEqualTo(21);
        assertThat(batch.imported()).isZero();
        assertThat(batch.duplicates()).isZero();
        assertThat(batch.rejected()).isEqualTo(21);
        assertThat(batch.errors()).hasSize(20);
        assertThat(database.caseRepository().count()).isZero();
        assertThat(batchCount()).isEqualTo(1);

        ImportService.Result replay = importCsv(csv.toString());

        assertThat(replay.replayed()).isTrue();
        assertThat(replay.batch().id()).isEqualTo(batch.id());
        assertThat(replay.batch().rejected()).isEqualTo(21);
        assertThat(replay.batch().errors()).hasSize(20);
        assertThat(batchCount()).isEqualTo(1);
    }

    @Test
    void capacityFailureRollsBackTheWholeImport() {
        seedCases(4_999);
        long batchesBefore = batchCount();

        BusinessException tooLarge = catchThrowableOfType(
                () -> importCsv(HEADER + "\n"
                        + row("600", "Product A", null, "Issue A", null,
                        "First capacity narrative", "Company A", "Closed", "")
                        + row("601", "Product A", null, "Issue A", null,
                        "Second capacity narrative", "Company A", "Closed", "")),
                BusinessException.class);

        assertThat(tooLarge).isNotNull();
        assertThat(tooLarge.status()).isEqualTo(409);
        assertThat(tooLarge.code()).isEqualTo("KNOWLEDGE_BASE_LIMIT_EXCEEDED");
        assertThat(database.caseRepository().count()).isEqualTo(4_999);
        assertThat(batchCount()).isEqualTo(batchesBefore);

        ImportService.Result lastSlot = importCsv(HEADER + "\n"
                + row("700", "Product A", null, "Issue A", null,
                "The last capacity narrative", "Company A", "Closed", ""));

        assertThat(lastSlot.batch().imported()).isEqualTo(1);
        assertThat(database.caseRepository().count()).isEqualTo(5_000);
        assertThat(batchCount()).isEqualTo(batchesBefore + 1);

        BusinessException full = catchThrowableOfType(
                () -> importCsv(HEADER + "\n"
                        + row("701", "Product A", null, "Issue A", null,
                        "One too many narratives", "Company A", "Closed", "")),
                BusinessException.class);

        assertThat(full).isNotNull();
        assertThat(full.status()).isEqualTo(409);
        assertThat(full.code()).isEqualTo("KNOWLEDGE_BASE_LIMIT_EXCEEDED");
        assertThat(database.caseRepository().count()).isEqualTo(5_000);
        assertThat(batchCount()).isEqualTo(batchesBefore + 1);
    }

    @Test
    void metadataIsValidatedAndTheArchiveUrlIsNeverFetched() {
        assertMetadataRejected("http://example.test/archive.zip", "sample.csv", PERIOD_START, PERIOD_END);
        assertMetadataRejected("https://example.test/" + "a".repeat(2_100), "sample.csv",
                PERIOD_START, PERIOD_END);
        assertMetadataRejected("https://example.test/archive.zip", "dir/sample.csv",
                PERIOD_START, PERIOD_END);
        assertMetadataRejected("https://example.test/archive.zip", "dir\\sample.csv",
                PERIOD_START, PERIOD_END);
        assertMetadataRejected("https://example.test/archive.zip", "s".repeat(256),
                PERIOD_START, PERIOD_END);
        assertMetadataRejected("https://example.test/archive.zip", "  ",
                PERIOD_START, PERIOD_END);
        assertMetadataRejected("https://example.test/archive.zip", "sample.csv",
                PERIOD_END, PERIOD_START);

        ImportMetadataValue normalized = new ImportMetadataValue(
                "  https://example.test/archive.zip  ", "  sample.csv  ",
                PERIOD_START, PERIOD_END);
        assertThat(normalized.archiveUrl()).isEqualTo("https://example.test/archive.zip");
        assertThat(normalized.sourceFileName()).isEqualTo("sample.csv");

        ImportMetadataValue unreachable = new ImportMetadataValue(
                "https://192.0.2.1/never-fetched.zip", "sample.csv", PERIOD_START, PERIOD_END);
        ImportService.Result result = service.importCsv(
                new ByteArrayInputStream((HEADER + "\n"
                                + row("800", "Product A", null, "Issue A", null,
                                "A narrative imported without network access",
                                "Company A", "Closed", ""))
                        .getBytes(StandardCharsets.UTF_8)),
                unreachable);

        assertThat(result.batch().imported()).isEqualTo(1);
    }

    @Test
    void novelCategoriesAreImportedAndExpandTheCatalog() {
        String csv = HEADER + "\n"
                + row("900", "Novel product", "Novel sub-product", "Novel issue", "Novel sub-issue",
                "A novel category narrative", "Novel company", "New response", "2024-11-08");

        importCsv(csv);

        List<CategoryValues> catalog = new CategoryRepository(database.database()).list();
        assertThat(catalog).containsExactly(new CategoryValues(
                "Novel product", "Novel sub-product", "Novel issue", "Novel sub-issue"));
    }

    @Test
    void importsTheCommittedSyntheticFixture() throws Exception {
        byte[] fixture;
        try (InputStream input = getClass().getResourceAsStream("/fixtures/cfpb-synthetic.csv")) {
            assertThat(input).isNotNull();
            fixture = input.readAllBytes();
        }

        ImportService.Result result = service.importCsv(
                new ByteArrayInputStream(fixture), metadata());

        assertThat(result.batch().processed()).isEqualTo(3);
        assertThat(result.batch().imported()).isEqualTo(3);
        assertThat(result.batch().duplicates()).isZero();
        assertThat(result.batch().rejected()).isZero();
        assertThat(database.caseRepository().count()).isEqualTo(3);
    }

    private ImportService.Result importCsv(String csv) {
        return service.importCsv(
                new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)), metadata());
    }

    private static ImportMetadataValue metadata() {
        return new ImportMetadataValue(
                "https://files.consumerfinance.gov/f/documents/synthetic-archive.zip",
                "finresolve-sample.csv",
                PERIOD_START,
                PERIOD_END);
    }

    private CaseRecord caseWithComplaintId(String complaintId) {
        return database.caseRepository().snapshot().stream()
                .filter(record -> record.provenance() != null
                        && complaintId.equals(record.provenance().complaintId()))
                .findFirst()
                .orElseThrow();
    }

    private long batchCount() {
        Long count = database.jdbc().queryForObject(
                "SELECT COUNT(*) FROM import_batches", Long.class);
        return count == null ? 0 : count;
    }

    private void seedCases(int count) {
        UUID batchId = UUID.randomUUID();
        database.jdbc().update(
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
                Timestamps.format(NOW),
                count,
                count);

        List<Object[]> rows = new ArrayList<>(count);
        String timestamp = Timestamps.format(NOW);
        for (int index = 0; index < count; index++) {
            rows.add(new Object[] {
                UUID.randomUUID().toString(),
                "CFPB",
                String.valueOf(800_000_000L + index),
                batchId.toString(),
                "2024-11-12",
                "Seed product",
                "Seed issue",
                "Seed narrative " + index,
                timestamp,
                timestamp
            });
        }
        database.jdbc().batchUpdate(
                """
                INSERT INTO cases (
                    id, source, complaint_id, batch_id, received_date, product,
                    issue, narrative, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                rows);
    }

    private static void assertMetadataRejected(
            String archiveUrl, String sourceFileName, LocalDate start, LocalDate end) {
        BusinessException failure = catchThrowableOfType(
                () -> new ImportMetadataValue(archiveUrl, sourceFileName, start, end),
                BusinessException.class);
        assertThat(failure).as("metadata %s / %s", archiveUrl, sourceFileName).isNotNull();
        assertThat(failure.status()).isEqualTo(400);
        assertThat(failure.code()).isEqualTo("VALIDATION_ERROR");
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
}
