package id.ac.finresolve.service.cases;

import id.ac.finresolve.domain.cases.CaseRecord;
import id.ac.finresolve.domain.cases.ComplaintInput;
import id.ac.finresolve.domain.cases.RetainCommand;
import id.ac.finresolve.support.HttpStatusOf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import id.ac.finresolve.repository.category.CategoryRepository;
import id.ac.finresolve.service.category.CategoryService;
import id.ac.finresolve.domain.category.CategoryValues;
import id.ac.finresolve.repository.db.Timestamps;
import id.ac.finresolve.domain.error.BusinessException;
import id.ac.finresolve.domain.importing.ImportMetadataValue;
import id.ac.finresolve.repository.importing.ImportRepository;
import id.ac.finresolve.service.importing.ImportService;
import id.ac.finresolve.support.TestDatabase;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RetentionServiceTest {

    private static final CategoryValues CHECKING = new CategoryValues(
            "Checking or savings account", "Checking account", "Managing an account", null);
    private static final CategoryValues CHECKING_OTHER_ISSUE = new CategoryValues(
            "Checking or savings account", "Checking account", "Opening an account", null);
    private static final String CSV_HEADER =
            "Complaint ID,Product,Sub-product,Issue,Sub-issue,"
                    + "Consumer complaint narrative,Company,Company response to consumer,Date received";
    private static final LocalDate PERIOD_START = LocalDate.parse("2024-11-01");
    private static final LocalDate PERIOD_END = LocalDate.parse("2024-12-31");

    @TempDir
    private Path tempDirectory;

    private TestDatabase database;
    private MutableClock clock;
    private RetentionService service;
    private UUID catalogCaseId;
    private long nextComplaintId = 900_000_000L;

    @BeforeEach
    void setUp() {
        database = TestDatabase.create(tempDirectory);
        clock = new MutableClock(Instant.parse("2026-10-26T10:00:00Z"));
        service = serviceFor(database);
        UUID catalogBatchId = database.seedBatch(TestDatabase.sampleBatch());
        catalogCaseId = seedCfpb(
                catalogBatchId, CHECKING, "A transfer is missing from my checking account.");
        seedCfpb(
                catalogBatchId,
                CHECKING_OTHER_ISSUE,
                "I could not open a checking account.");
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    @Test
    void createsAnalystCaseWithNotesAndNoProvenance() {
        CaseRecord created = service.create(command(
                CHECKING,
                "My transfer is still missing.",
                LocalDate.parse("2024-12-01"),
                "Example Bank",
                "Closed with explanation",
                notes("Reviewed transfer evidence.", CaseRecord.Outcome.UNKNOWN, "Awaiting reply."),
                List.of(catalogCaseId)));

        assertThat(created.source()).isEqualTo(CaseRecord.Source.ANALYST);
        assertThat(created.provenance()).isNull();
        assertThat(created.categories()).isEqualTo(CHECKING);
        assertThat(created.narrative()).isEqualTo("My transfer is still missing.");
        assertThat(created.receivedDate()).isEqualTo(LocalDate.parse("2024-12-01"));
        assertThat(created.company()).isEqualTo("Example Bank");
        assertThat(created.companyResponse()).isEqualTo("Closed with explanation");
        assertThat(created.createdAt()).isEqualTo(clock.instant());
        assertThat(created.updatedAt()).isEqualTo(clock.instant());
        assertThat(created.notes()).isEqualTo(
                notes("Reviewed transfer evidence.", CaseRecord.Outcome.UNKNOWN, "Awaiting reply."));
        assertThat(created.referenceCaseIds()).containsExactly(catalogCaseId);
        assertThat(database.caseRepository().find(created.id())).contains(created);
    }

    @Test
    void replacementPreservesIdentityAndClearsEveryOmittedMutableField() {
        CaseRecord created = service.create(command(
                CHECKING,
                "My original narrative.",
                LocalDate.parse("2024-12-01"),
                "Example Bank",
                "Closed with explanation",
                notes("Original handling.", CaseRecord.Outcome.UNRESOLVED, "Original evaluation."),
                List.of(catalogCaseId)));
        clock.advance(Duration.ofSeconds(5));

        CaseRecord replaced = service.replace(created.id(), command(
                CHECKING_OTHER_ISSUE,
                "A completely different narrative.",
                null,
                null,
                null,
                notes("Replacement handling.", CaseRecord.Outcome.RESOLVED, null),
                List.of()));

        assertThat(replaced.id()).isEqualTo(created.id());
        assertThat(replaced.createdAt()).isEqualTo(created.createdAt());
        assertThat(replaced.updatedAt()).isAfter(created.updatedAt());
        assertThat(replaced.source()).isEqualTo(CaseRecord.Source.ANALYST);
        assertThat(replaced.provenance()).isNull();
        assertThat(replaced.categories()).isEqualTo(CHECKING_OTHER_ISSUE);
        assertThat(replaced.narrative()).isEqualTo("A completely different narrative.");
        assertThat(replaced.receivedDate()).isNull();
        assertThat(replaced.company()).isNull();
        assertThat(replaced.companyResponse()).isNull();
        assertThat(replaced.referenceCaseIds()).isEmpty();
        assertThat(replaced.notes()).isEqualTo(
                notes("Replacement handling.", CaseRecord.Outcome.RESOLVED, null));
        assertThat(database.caseRepository().find(created.id())).contains(replaced);
    }

    @Test
    void notesAndReferencesSurviveRestart() {
        UUID analystReference = seedAnalystReference("An analyst reference.");
        CaseRecord created = service.create(command(
                CHECKING,
                "A retained case with two references.",
                null,
                null,
                null,
                notes("Handling notes.", CaseRecord.Outcome.PARTIALLY_RESOLVED, "Evaluation notes."),
                List.of(catalogCaseId, analystReference)));

        database.close();
        database = TestDatabase.reopen(database.file());

        CaseRecord loaded = database.caseRepository().find(created.id()).orElseThrow();
        assertThat(loaded.notes()).isEqualTo(created.notes());
        assertThat(loaded.referenceCaseIds()).containsExactly(catalogCaseId, analystReference);
        assertThat(loaded.createdAt()).isEqualTo(created.createdAt());
        assertThat(loaded.updatedAt()).isEqualTo(created.updatedAt());
    }

    @Test
    void missingDuplicateSelfAndOversizedReferencesFailWithoutChangingData() {
        CaseRecord created = service.create(command(
                CHECKING,
                "The retained case must stay unchanged.",
                null,
                null,
                null,
                notes("Original handling.", CaseRecord.Outcome.UNKNOWN, null),
                List.of(catalogCaseId)));
        CaseRecord before = database.caseRepository().find(created.id()).orElseThrow();
        long countBefore = database.caseRepository().count();

        List<List<UUID>> invalidReferences = List.of(
                List.of(UUID.randomUUID()),
                List.of(catalogCaseId, catalogCaseId),
                List.of(created.id()),
                java.util.stream.IntStream.range(0, 21)
                        .mapToObj(ignored -> UUID.randomUUID())
                        .toList());

        for (List<UUID> references : invalidReferences) {
            BusinessException failure = catchThrowableOfType(
                    () -> service.replace(created.id(), command(
                            CHECKING,
                            "A rejected replacement.",
                            null,
                            null,
                            null,
                            notes("Rejected handling.", CaseRecord.Outcome.UNKNOWN, null),
                            references)),
                    BusinessException.class);

            assertThat(failure).as("references %s", references).isNotNull();
            assertThat(HttpStatusOf.of(failure)).isEqualTo(400);
            assertThat(failure.code()).isEqualTo("INVALID_REFERENCE");
            assertThat(database.caseRepository().find(created.id())).contains(before);
        }

        BusinessException createFailure = catchThrowableOfType(
                () -> service.create(command(
                        CHECKING,
                        "A rejected new case.",
                        null,
                        null,
                        null,
                        notes("Rejected handling.", CaseRecord.Outcome.UNKNOWN, null),
                        List.of(UUID.randomUUID()))),
                BusinessException.class);

        assertThat(createFailure).isNotNull();
        assertThat(createFailure.code()).isEqualTo("INVALID_REFERENCE");
        assertThat(database.caseRepository().count()).isEqualTo(countBefore);
    }

    @Test
    void blankAndOversizedNotesAreRejectedWithoutWriting() {
        long countBefore = database.caseRepository().count();

        assertNotesRejected(
                notes("   ", CaseRecord.Outcome.UNKNOWN, null), "handlingNotes", "REQUIRED");
        assertNotesRejected(
                notes("x".repeat(10_001), CaseRecord.Outcome.UNKNOWN, null),
                "handlingNotes",
                "TOO_LONG");
        assertNotesRejected(
                notes("Valid handling.", CaseRecord.Outcome.UNKNOWN, "y".repeat(10_001)),
                "evaluationNotes",
                "TOO_LONG");

        assertThat(database.caseRepository().count()).isEqualTo(countBefore);
    }

    @Test
    void everyOutcomeEnumCanBeRetained() {
        for (CaseRecord.Outcome outcome : CaseRecord.Outcome.values()) {
            CaseRecord created = service.create(command(
                    CHECKING,
                    "A narrative for " + outcome + ".",
                    null,
                    null,
                    null,
                    notes("Handling for " + outcome + ".", outcome, null),
                    List.of()));

            assertThat(database.caseRepository().find(created.id()).orElseThrow().notes()
                            .outcomeAssessment())
                    .isEqualTo(outcome);
        }
    }

    @Test
    void missingReplacementIs404AndCfpbReplacementIs409() {
        BusinessException missing = catchThrowableOfType(
                () -> service.replace(UUID.randomUUID(), command(
                        CHECKING,
                        "No such case.",
                        null,
                        null,
                        null,
                        notes("Handling.", CaseRecord.Outcome.UNKNOWN, null),
                        List.of())),
                BusinessException.class);

        assertThat(missing).isNotNull();
        assertThat(HttpStatusOf.of(missing)).isEqualTo(404);
        assertThat(missing.code()).isEqualTo("CASE_NOT_FOUND");

        BusinessException immutable = catchThrowableOfType(
                () -> service.replace(catalogCaseId, command(
                        CHECKING,
                        "Attempt to rewrite an archived case.",
                        null,
                        null,
                        null,
                        notes("Handling.", CaseRecord.Outcome.UNKNOWN, null),
                        List.of())),
                BusinessException.class);

        assertThat(immutable).isNotNull();
        assertThat(HttpStatusOf.of(immutable)).isEqualTo(409);
        assertThat(immutable.code()).isEqualTo("IMMUTABLE_CFPB_CASE");
        assertThat(database.caseRepository().find(catalogCaseId).orElseThrow().narrative())
                .isEqualTo("A transfer is missing from my checking account.");
    }

    @Test
    void emptyCatalogRejectsRetentionAndReplacement() throws Exception {
        Path emptyDirectory = Files.createDirectory(tempDirectory.resolve("empty-catalog"));
        try (TestDatabase empty = TestDatabase.create(emptyDirectory)) {
            RetentionService emptyService = serviceFor(empty);
            CaseRecord analyst = insertAnalyst(empty, clock.instant());

            BusinessException onCreate = catchThrowableOfType(
                    () -> emptyService.create(withoutReferences()),
                    BusinessException.class);
            assertThat(onCreate).isNotNull();
            assertThat(HttpStatusOf.of(onCreate)).isEqualTo(409);
            assertThat(onCreate.code()).isEqualTo("EMPTY_CATEGORY_CATALOG");

            BusinessException onReplace = catchThrowableOfType(
                    () -> emptyService.replace(analyst.id(), withoutReferences()),
                    BusinessException.class);
            assertThat(onReplace).isNotNull();
            assertThat(HttpStatusOf.of(onReplace)).isEqualTo(409);
            assertThat(onReplace.code()).isEqualTo("EMPTY_CATEGORY_CATALOG");
            assertThat(empty.caseRepository().count()).isEqualTo(1);
        }
    }

    @Test
    void repeatedPostCreatesTwoDifferentCases() {
        CaseRecord first = service.create(validCommand());
        CaseRecord second = service.create(validCommand());

        assertThat(first.id()).isNotEqualTo(second.id());
        assertThat(database.caseRepository().count()).isEqualTo(4);
    }

    @Test
    void capacityRejectsNewCasesButStillAllowsAReplacement() {
        seedFillerAnalystCases(4_999 - (int) database.caseRepository().count());
        CaseRecord last = service.create(validCommand());
        assertThat(database.caseRepository().count()).isEqualTo(5_000);

        BusinessException full = catchThrowableOfType(
                () -> service.create(validCommand()),
                BusinessException.class);

        assertThat(full).isNotNull();
        assertThat(HttpStatusOf.of(full)).isEqualTo(409);
        assertThat(full.code()).isEqualTo("KNOWLEDGE_BASE_LIMIT_EXCEEDED");
        assertThat(database.caseRepository().count()).isEqualTo(5_000);

        CaseRecord replaced = service.replace(last.id(), command(
                CHECKING,
                "Replacement at capacity.",
                null,
                null,
                null,
                notes("Replacement handling.", CaseRecord.Outcome.RESOLVED, null),
                List.of()));

        assertThat(replaced.id()).isEqualTo(last.id());
        assertThat(database.caseRepository().count()).isEqualTo(5_000);
    }

    @Test
    void twoWritersCompetingForTheLastSlotLeaveOneCompleteCase() throws Exception {
        seedFillerAnalystCases(4_999 - (int) database.caseRepository().count());
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        List<Object> outcomes = runTogether(ready, start,
                () -> service.create(validCommand()),
                () -> service.create(validCommand()));

        assertThat(outcomes).filteredOn(CaseRecord.class::isInstance).hasSize(1);
        assertThat(outcomes).filteredOn(BusinessException.class::isInstance).hasSize(1);

        BusinessException failure = (BusinessException) outcomes.stream()
                .filter(BusinessException.class::isInstance)
                .findFirst()
                .orElseThrow();
        assertThat(HttpStatusOf.of(failure)).isEqualTo(409);
        assertThat(failure.code()).isEqualTo("KNOWLEDGE_BASE_LIMIT_EXCEEDED");
        assertThat(database.caseRepository().count()).isEqualTo(5_000);
        assertThat(count("analyst_notes")).isEqualTo(1);
        assertThat(count("case_references")).isEqualTo(1);
    }

    @Test
    void retentionAndImportCompetitionNeverExceedsCapacity() throws Exception {
        seedFillerAnalystCases(4_999 - (int) database.caseRepository().count());
        ImportService imports = new ImportService(
                new ImportRepository(database.database()),
                database.caseRepository(),
                database.database());
        String csv = CSV_HEADER + "\n"
                + row("970000001", "Concurrent import narrative one.")
                + row("970000002", "Concurrent import narrative two.")
                + row("970000003", "Concurrent import narrative three.");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        List<Object> outcomes = runTogether(ready, start,
                () -> service.create(validCommand()),
                () -> imports.importCsv(
                        new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)),
                        importMetadata()));

        assertThat(outcomes).filteredOn(CaseRecord.class::isInstance).hasSize(1);
        assertThat(outcomes).filteredOn(ImportService.Result.class::isInstance).isEmpty();
        BusinessException failure = (BusinessException) outcomes.stream()
                .filter(BusinessException.class::isInstance)
                .findFirst()
                .orElseThrow();
        assertThat(HttpStatusOf.of(failure)).isEqualTo(409);
        assertThat(failure.code()).isEqualTo("KNOWLEDGE_BASE_LIMIT_EXCEEDED");
        assertThat(database.caseRepository().count()).isEqualTo(5_000);
        assertThat(count("import_batches")).isEqualTo(1);
    }

    private void assertNotesRejected(
            CaseRecord.Notes notes, String field, String code) {
        BusinessException failure = catchThrowableOfType(
                () -> service.create(command(
                        CHECKING,
                        "A valid narrative.",
                        null,
                        null,
                        null,
                        notes,
                        List.of())),
                BusinessException.class);

        assertThat(failure).isNotNull();
        assertThat(HttpStatusOf.of(failure)).isEqualTo(400);
        assertThat(failure.code()).isEqualTo("VALIDATION_ERROR");
        assertThat(failure.fieldErrors())
                .anySatisfy(problem -> {
                    assertThat(problem.field()).isEqualTo(field);
                    assertThat(problem.code()).isEqualTo(code);
                });
    }

    private RetentionService serviceFor(TestDatabase target) {
        return new RetentionService(
                target.caseRepository(),
                new CategoryService(new CategoryRepository(target.database())),
                target.database(),
                clock,
                UUID::randomUUID);
    }

    private CaseRecord insertAnalyst(TestDatabase target, Instant timestamp) {
        UUID id = UUID.randomUUID();
        CaseRecord record = new CaseRecord(
                id,
                CaseRecord.Source.ANALYST,
                CHECKING,
                "Existing analyst narrative.",
                null,
                null,
                null,
                timestamp,
                timestamp,
                null,
                notes("Existing handling.", CaseRecord.Outcome.UNKNOWN, null),
                List.of());
        target.caseRepository().insert(record);
        return record;
    }

    private UUID seedAnalystReference(String narrative) {
        UUID id = UUID.randomUUID();
        database.caseRepository().insert(new CaseRecord(
                id,
                CaseRecord.Source.ANALYST,
                CHECKING,
                narrative,
                null,
                null,
                null,
                clock.instant(),
                clock.instant(),
                null,
                notes("Reference handling.", CaseRecord.Outcome.UNKNOWN, null),
                List.of()));
        return id;
    }

    private UUID seedCfpb(
            UUID batchId, CategoryValues categories, String narrative) {
        UUID id = UUID.randomUUID();
        database.caseRepository().insert(new CaseRecord(
                id,
                CaseRecord.Source.CFPB,
                categories,
                narrative,
                LocalDate.parse("2024-11-12"),
                "Example Bank",
                "Closed with explanation",
                clock.instant(),
                clock.instant(),
                new CaseRecord.Provenance(
                        batchId,
                        String.valueOf(nextComplaintId++),
                        "https://files.consumerfinance.gov/f/documents/synthetic-archive.zip",
                        "finresolve-sample.csv",
                        PERIOD_START,
                        PERIOD_END,
                        "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"),
                null,
                List.of()));
        return id;
    }

    private void seedFillerAnalystCases(int count) {
        List<Object[]> rows = new ArrayList<>(count);
        String timestamp = Timestamps.format(clock.instant());
        for (int index = 0; index < count; index++) {
            rows.add(new Object[] {
                UUID.randomUUID().toString(),
                CHECKING.product(),
                CHECKING.issue(),
                "Capacity filler narrative " + index,
                timestamp,
                timestamp
            });
        }
        database.database().write(() -> {
            database.jdbc().batchUpdate(
                    """
                    INSERT INTO cases (
                        id, source, product, issue, narrative, created_at, updated_at
                    ) VALUES (?, 'ANALYST', ?, ?, ?, ?, ?)
                    """,
                    rows);
            return null;
        });
    }

    private List<Object> runTogether(
            CountDownLatch ready, CountDownLatch start, Callable<?>... tasks) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(tasks.length);
        try {
            List<Future<Object>> futures = new ArrayList<>(tasks.length);
            for (Callable<?> task : tasks) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await(10, TimeUnit.SECONDS);
                    try {
                        return task.call();
                    } catch (BusinessException failure) {
                        return failure;
                    }
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<Object> results = new ArrayList<>(futures.size());
            for (Future<Object> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    private long count(String table) {
        Long value = database.jdbc().queryForObject(
                "SELECT COUNT(*) FROM " + table, Long.class);
        return value == null ? 0 : value;
    }

    private RetainCommand validCommand() {
        return command(
                CHECKING,
                "A valid retained narrative about a missing transfer.",
                null,
                null,
                null,
                notes("Reviewed the available evidence.", CaseRecord.Outcome.UNKNOWN, null),
                List.of(catalogCaseId));
    }

    private RetainCommand withoutReferences() {
        return command(
                CHECKING,
                "A valid retained narrative without references.",
                null,
                null,
                null,
                notes("Reviewed the available evidence.", CaseRecord.Outcome.UNKNOWN, null),
                List.of());
    }

    private static RetainCommand command(
            CategoryValues categories,
            String narrative,
            LocalDate receivedDate,
            String company,
            String companyResponse,
            CaseRecord.Notes notes,
            List<UUID> references) {
        return new RetainCommand(
                new ComplaintInput(categories, narrative, receivedDate, company, companyResponse),
                notes,
                references);
    }

    private static CaseRecord.Notes notes(
            String handlingNotes, CaseRecord.Outcome outcome, String evaluationNotes) {
        return new CaseRecord.Notes(handlingNotes, outcome, evaluationNotes);
    }

    private static String row(String complaintId, String narrative) {
        return String.join(",",
                complaintId,
                "Import product",
                "",
                "Import issue",
                "",
                "\"" + narrative + "\"",
                "Example Bank",
                "Closed",
                "") + "\n";
    }

    private static ImportMetadataValue importMetadata() {
        return new ImportMetadataValue(
                "https://files.consumerfinance.gov/f/documents/synthetic-archive.zip",
                "concurrent-import.csv",
                PERIOD_START,
                PERIOD_END);
    }

    private static final class MutableClock extends Clock {

        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
