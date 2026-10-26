package id.ac.finresolve.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.ac.finresolve.cases.CaseRecord;
import id.ac.finresolve.cases.CaseRepository;
import id.ac.finresolve.categories.CategoryValues;
import id.ac.finresolve.imports.BatchRecord;
import id.ac.finresolve.support.TestDatabase;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataAccessException;

class DatabaseTest {

    private static final Instant BASE_TIME = Instant.parse("2026-10-26T10:00:00Z");

    @TempDir
    Path tempDir;

    @Test
    void appliesRequiredPragmasAndMigrationBeforeUse() {
        try (TestDatabase db = TestDatabase.create(tempDir)) {
            assertThat(pragmaInt(db, "foreign_keys")).isEqualTo(1);
            assertThat(pragmaInt(db, "busy_timeout")).isEqualTo(5000);
            assertThat(pragmaString(db, "journal_mode")).isEqualToIgnoringCase("wal");

            assertThat(db.jdbc().queryForObject("SELECT COUNT(*) FROM schema_versions", Long.class))
                    .isEqualTo(1L);
            assertThat(db.jdbc().queryForObject("SELECT version FROM schema_versions", Integer.class))
                    .isEqualTo(1);
            assertThat(tableExists(db, "import_batches")).isTrue();
            assertThat(tableExists(db, "cases")).isTrue();
            assertThat(tableExists(db, "analyst_notes")).isTrue();
            assertThat(tableExists(db, "case_references")).isTrue();
        }
    }

    @Test
    void appliedMigrationVersionSurvivesCloseAndReopen() {
        Path file;
        try (TestDatabase db = TestDatabase.create(tempDir)) {
            file = db.file();
            assertThat(db.jdbc().queryForObject("SELECT COUNT(*) FROM schema_versions", Long.class))
                    .isEqualTo(1L);
        }

        try (TestDatabase reopened = TestDatabase.reopen(file)) {
            assertThat(reopened.jdbc().queryForObject("SELECT COUNT(*) FROM schema_versions", Long.class))
                    .isEqualTo(1L);
            assertThat(reopened.jdbc().queryForObject("SELECT version FROM schema_versions", Integer.class))
                    .isEqualTo(1);
        }
    }

    @Test
    void casesNotesReferencesProvenanceAndUtcTimestampsSurviveRestart() {
        UUID targetId = UUID.fromString("00000000-0000-0000-0000-000000000002");
        UUID analystId = UUID.fromString("00000000-0000-0000-0000-000000000003");
        UUID cfpbId = UUID.fromString("00000000-0000-0000-0000-000000000004");
        Instant targetCreated = Instant.parse("2026-10-26T10:15:30.123456Z");
        Instant analystCreated = Instant.parse("2026-10-26T10:16:00Z");
        Instant analystUpdated = Instant.parse("2026-10-26T10:16:30.999Z");

        BatchRecord batch = TestDatabase.sampleBatch();
        CaseRecord target = analyst(targetId, targetCreated, targetCreated, "Target narrative", "Target notes", List.of());
        CaseRecord analyst = analyst(
                analystId,
                analystCreated,
                analystUpdated,
                "Analyst narrative",
                "Reviewed the transfer trace.",
                List.of(targetId));
        CaseRecord cfpb = cfpb(
                cfpbId,
                batch,
                "9000001",
                Instant.parse("2026-10-26T10:17:00Z"));

        Path file;
        try (TestDatabase db = TestDatabase.create(tempDir)) {
            db.seedBatch(batch);
            db.database().write(() -> {
                CaseRepository repository = db.caseRepository();
                repository.insert(target);
                repository.insert(analyst);
                repository.insert(cfpb);
                return null;
            });
            file = db.file();
        }

        try (TestDatabase reopened = TestDatabase.reopen(file)) {
            CaseRepository repository = reopened.caseRepository();
            assertThat(repository.find(targetId)).contains(target);
            assertThat(repository.find(analystId)).contains(analyst);
            assertThat(repository.find(cfpbId)).contains(cfpb);

            CaseRecord found = repository.find(analystId).orElseThrow();
            assertThat(found.createdAt()).isEqualTo(analystCreated);
            assertThat(found.updatedAt()).isEqualTo(analystUpdated);
            assertThat(found.referenceCaseIds()).containsExactly(targetId);
            assertThat(found.notes().handlingNotes()).isEqualTo("Reviewed the transfer trace.");

            CaseRecord foundCfpb = repository.find(cfpbId).orElseThrow();
            assertThat(foundCfpb.provenance()).isEqualTo(cfpb.provenance());
            assertThat(foundCfpb.notes()).isNull();
            assertThat(foundCfpb.referenceCaseIds()).isEmpty();
        }
    }

    @Test
    void foreignKeyViolationFails() {
        try (TestDatabase db = TestDatabase.create(tempDir)) {
            UUID missingBatch = UUID.randomUUID();
            String now = BASE_TIME.toString();
            assertThatThrownBy(() -> db.jdbc().update(
                            """
                            INSERT INTO cases (
                                id, source, complaint_id, batch_id, product, issue, narrative,
                                created_at, updated_at
                            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """,
                            UUID.randomUUID().toString(),
                            "CFPB",
                            "9000002",
                            missingBatch.toString(),
                            "Checking or savings account",
                            "Managing an account",
                            "Narrative",
                            now,
                            now))
                    .isInstanceOf(DataAccessException.class);
        }
    }

    @Test
    void selfReferenceFailsInRepositoryAndDatabase() {
        UUID id = UUID.randomUUID();
        try (TestDatabase db = TestDatabase.create(tempDir)) {
            CaseRepository repository = db.caseRepository();
            CaseRecord withSelf = analyst(id, BASE_TIME, BASE_TIME, "Narrative", "Notes", List.of(id));
            assertThatThrownBy(() -> repository.insert(withSelf))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("itself");

            CaseRecord plain = analyst(id, BASE_TIME, BASE_TIME, "Narrative", "Notes", List.of());
            repository.insert(plain);
            assertThatThrownBy(() -> db.jdbc().update(
                            "INSERT INTO case_references (case_id, referenced_case_id, position) VALUES (?, ?, ?)",
                            id.toString(),
                            id.toString(),
                            0))
                    .isInstanceOf(DataAccessException.class);
        }
    }

    @Test
    void duplicateComplaintIdFails() {
        BatchRecord batch = TestDatabase.sampleBatch();
        try (TestDatabase db = TestDatabase.create(tempDir)) {
            db.seedBatch(batch);
            CaseRepository repository = db.caseRepository();
            repository.insert(cfpb(UUID.randomUUID(), batch, "9000003", BASE_TIME));
            CaseRecord duplicate =
                    cfpb(UUID.randomUUID(), batch, "9000003", BASE_TIME.plusSeconds(1));

            assertThatThrownBy(() -> repository.insert(duplicate))
                    .isInstanceOf(DataAccessException.class)
                    .hasMessageContaining("UNIQUE");
            assertThat(repository.count()).isEqualTo(1L);
        }
    }

    @Test
    void incompatibleSourceNotesAndProvenanceWritesFail() {
        BatchRecord batch = TestDatabase.sampleBatch();
        UUID cfpbId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        try (TestDatabase db = TestDatabase.create(tempDir)) {
            db.seedBatch(batch);
            CaseRepository repository = db.caseRepository();
            CaseRecord cfpb = cfpb(cfpbId, batch, "9000004", BASE_TIME);
            repository.insert(cfpb);
            CaseRecord target = analyst(targetId, BASE_TIME, BASE_TIME, "Target", "Target notes", List.of());
            repository.insert(target);

            assertThatThrownBy(() -> repository.insert(cfpbWithNotes(cfpbId, batch, "9000005")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("notes");
            assertThatThrownBy(() -> repository.insert(cfpbWithReference(cfpbId, batch, "9000006", targetId)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("reference");
            assertThatThrownBy(() -> repository.insert(cfpbWithoutProvenance(UUID.randomUUID())))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("provenance");
            assertThatThrownBy(() -> repository.insert(analystWithProvenance(UUID.randomUUID(), batch)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("provenance");
            assertThatThrownBy(() -> repository.insert(
                            new CaseRecord(
                                    UUID.randomUUID(),
                                    CaseRecord.Source.ANALYST,
                                    categories(),
                                    "Narrative",
                                    null,
                                    "Company",
                                    null,
                                    BASE_TIME,
                                    BASE_TIME,
                                    null,
                                    null,
                                    List.of())))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("notes");
            assertThatThrownBy(() -> repository.insert(analyst(
                            UUID.randomUUID(),
                            BASE_TIME,
                            BASE_TIME,
                            "Narrative",
                            "   ",
                            List.of())))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("notes");

            assertThatThrownBy(() -> db.jdbc().update(
                            """
                            INSERT INTO analyst_notes (
                                case_id, handling_notes, outcome_assessment, evaluation_notes
                            ) VALUES (?, ?, ?, ?)
                            """,
                            cfpbId.toString(),
                            "Forbidden note",
                            "UNKNOWN",
                            null))
                    .isInstanceOf(DataAccessException.class);

            assertThatThrownBy(() -> db.jdbc().update(
                            "INSERT INTO case_references (case_id, referenced_case_id, position) VALUES (?, ?, ?)",
                            cfpbId.toString(),
                            targetId.toString(),
                            0))
                    .isInstanceOf(DataAccessException.class);

            assertThatThrownBy(() -> db.jdbc().update(
                            """
                            INSERT INTO cases (
                                id, source, product, issue, narrative, created_at, updated_at
                            ) VALUES (?, ?, ?, ?, ?, ?, ?)
                            """,
                            UUID.randomUUID().toString(),
                            "CFPB",
                            "Checking or savings account",
                            "Managing an account",
                            "Narrative",
                            BASE_TIME.toString(),
                            BASE_TIME.toString()))
                    .isInstanceOf(DataAccessException.class);
        }
    }

    @Test
    void failingMigrationRollsBackItsDdlAndVersionRow() {
        Path file = tempDir.resolve("failing.db");
        try (TestDatabase db = TestDatabase.open(file, List.of())) {
            MigrationRunner.Migration broken = new MigrationRunner.Migration(
                    1,
                    "broken",
                    """
                    CREATE TABLE migration_probe (id TEXT PRIMARY KEY);
                    CREATE TABLE migration_probe (id TEXT);
                    """);

            assertThatThrownBy(() -> new MigrationRunner(db.database(), List.of(broken)).migrate())
                    .isInstanceOf(MigrationException.class);

            assertThat(tableExists(db, "migration_probe")).isFalse();
            assertThat(db.jdbc().queryForObject(
                            "SELECT COUNT(*) FROM schema_versions WHERE version = 1", Long.class))
                    .isZero();
        }
    }

    @Test
    void changedChecksumForAnAppliedMigrationFailsStartup() {
        Path file = tempDir.resolve("checksum.db");
        MigrationRunner.Migration original = new MigrationRunner.Migration(
                1, "checksum-probe", "CREATE TABLE checksum_probe (id TEXT PRIMARY KEY);");
        try (TestDatabase ignored = TestDatabase.open(file, List.of(original))) {
            assertThat(ignored.jdbc().queryForObject(
                            "SELECT COUNT(*) FROM schema_versions WHERE version = 1", Long.class))
                    .isEqualTo(1L);
        }

        MigrationRunner.Migration changed = new MigrationRunner.Migration(
                1, "checksum-probe", "CREATE TABLE checksum_probe (id TEXT PRIMARY KEY, extra TEXT);");
        assertThatThrownBy(() -> TestDatabase.open(file, List.of(changed)))
                .isInstanceOf(MigrationException.class)
                .hasMessageContaining("checksum");

        try (TestDatabase reopened = TestDatabase.open(file, List.of(original))) {
            assertThat(tableExists(reopened, "checksum_probe")).isTrue();
        }
    }

    @Test
    void independentWriteLockFailsApplicationWriteWithTypedBusyExceptionAfterTimeout() throws Exception {
        try (TestDatabase db = TestDatabase.create(tempDir)) {
            long started = System.nanoTime();
            try (Connection lock = db.acquireWriteLock()) {
                assertThatThrownBy(() -> db.database().write(() -> {
                            db.seedBatch(TestDatabase.sampleBatch());
                            return null;
                        }))
                        .isInstanceOf(DatabaseBusyException.class);
            }
            long elapsedMillis = (System.nanoTime() - started) / 1_000_000L;
            assertThat(elapsedMillis)
                    .as("application write should wait for busy_timeout before failing")
                    .isGreaterThanOrEqualTo(4_000L);

            db.database().write(() -> {
                db.seedBatch(TestDatabase.sampleBatch());
                return null;
            });
            assertThat(db.jdbc().queryForObject("SELECT COUNT(*) FROM import_batches", Long.class))
                    .isEqualTo(1L);
        }
    }

    @Test
    void pageUsesStableOrderingSourceFilterAndLongOffsets() {
        UUID oldestId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID sameTimeOne = UUID.fromString("00000000-0000-0000-0000-000000000002");
        UUID sameTimeTwo = UUID.fromString("00000000-0000-0000-0000-000000000003");
        UUID cfpbId = UUID.fromString("00000000-0000-0000-0000-000000000004");
        BatchRecord batch = TestDatabase.sampleBatch();

        try (TestDatabase db = TestDatabase.create(tempDir)) {
            db.seedBatch(batch);
            CaseRepository repository = db.caseRepository();
            db.database().write(() -> {
                repository.insert(analyst(
                        oldestId, BASE_TIME.minusSeconds(2), BASE_TIME.minusSeconds(2), "Oldest", "Notes", List.of()));
                repository.insert(analyst(
                        sameTimeOne, BASE_TIME, BASE_TIME, "Same one", "Notes", List.of()));
                repository.insert(analyst(
                        sameTimeTwo, BASE_TIME, BASE_TIME, "Same two", "Notes", List.of()));
                repository.insert(cfpb(cfpbId, batch, "9000007", BASE_TIME.plusSeconds(1)));
                return null;
            });

            CaseRepository.Page first = repository.page(0, 2, null);
            assertThat(first.items()).extracting(CaseRecord::id).containsExactly(cfpbId, sameTimeOne);
            assertThat(first.page()).isZero();
            assertThat(first.size()).isEqualTo(2);
            assertThat(first.totalElements()).isEqualTo(4L);

            CaseRepository.Page second = repository.page(1, 2, null);
            assertThat(second.items()).extracting(CaseRecord::id).containsExactly(sameTimeTwo, oldestId);

            CaseRepository.Page beyond = repository.page(9, 2, null);
            assertThat(beyond.items()).isEmpty();
            assertThat(beyond.totalElements()).isEqualTo(4L);

            assertThat(repository.page(0, 10, CaseRecord.Source.ANALYST).items())
                    .extracting(CaseRecord::id)
                    .containsExactly(sameTimeOne, sameTimeTwo, oldestId);
            assertThat(repository.page(0, 10, CaseRecord.Source.CFPB).items())
                    .extracting(CaseRecord::id)
                    .containsExactly(cfpbId);
            assertThat(repository.count()).isEqualTo(4L);
            assertThat(repository.snapshot()).extracting(CaseRecord::id)
                    .containsExactly(cfpbId, sameTimeOne, sameTimeTwo, oldestId);
        }
    }

    @Test
    void replaceAnalystValidatesFirstPreservesCreatedAtAndReplacesNotesAndReferences() {
        UUID firstTargetId = UUID.randomUUID();
        UUID secondTargetId = UUID.randomUUID();
        UUID analystId = UUID.randomUUID();
        Instant originalCreated = BASE_TIME;
        Instant originalUpdated = BASE_TIME.plusMillis(100);

        try (TestDatabase db = TestDatabase.create(tempDir)) {
            CaseRepository repository = db.caseRepository();
            db.database().write(() -> {
                repository.insert(analyst(
                        firstTargetId, BASE_TIME, BASE_TIME, "First target", "Notes", List.of()));
                repository.insert(analyst(
                        secondTargetId, BASE_TIME, BASE_TIME, "Second target", "Notes", List.of()));
                repository.insert(new CaseRecord(
                        analystId,
                        CaseRecord.Source.ANALYST,
                        categories(),
                        "Original narrative",
                        null,
                        "Company",
                        "Original response",
                        originalCreated,
                        originalUpdated,
                        null,
                        new CaseRecord.Notes("Original notes", CaseRecord.Outcome.UNKNOWN, null),
                        List.of(firstTargetId)));
                return null;
            });

            Instant replacementUpdated = BASE_TIME.plusMillis(500);
            CaseRecord replacement = new CaseRecord(
                    analystId,
                    CaseRecord.Source.ANALYST,
                    new CategoryValues(
                            "Money transfer, virtual currency, or money service",
                            null,
                            "Fraud or scam",
                            null),
                    "Updated narrative",
                    LocalDate.parse("2024-12-01"),
                    "Updated company",
                    "Updated response",
                    BASE_TIME.minusSeconds(1_000),
                    replacementUpdated,
                    null,
                    new CaseRecord.Notes(
                            "Updated notes", CaseRecord.Outcome.PARTIALLY_RESOLVED, "Evaluation"),
                    List.of(secondTargetId));
            repository.replaceAnalyst(replacement);

            CaseRecord replaced = repository.find(analystId).orElseThrow();
            assertThat(replaced.createdAt()).isEqualTo(originalCreated);
            assertThat(replaced.updatedAt()).isEqualTo(replacementUpdated);
            assertThat(replaced.categories()).isEqualTo(replacement.categories());
            assertThat(replaced.narrative()).isEqualTo("Updated narrative");
            assertThat(replaced.receivedDate()).isEqualTo(LocalDate.parse("2024-12-01"));
            assertThat(replaced.company()).isEqualTo("Updated company");
            assertThat(replaced.companyResponse()).isEqualTo("Updated response");
            assertThat(replaced.notes()).isEqualTo(replacement.notes());
            assertThat(replaced.referenceCaseIds()).containsExactly(secondTargetId);
            assertThat(repository.count()).isEqualTo(3L);

            CaseRecord invalid = new CaseRecord(
                    analystId,
                    CaseRecord.Source.ANALYST,
                    categories(),
                    "Should not persist",
                    null,
                    null,
                    null,
                    BASE_TIME,
                    replacementUpdated.plusSeconds(1),
                    null,
                    null,
                    List.of());
            assertThatThrownBy(() -> repository.replaceAnalyst(invalid))
                    .isInstanceOf(IllegalArgumentException.class);
            CaseRecord unchanged = repository.find(analystId).orElseThrow();
            assertThat(unchanged.narrative()).isEqualTo("Updated narrative");
            assertThat(unchanged.notes()).isEqualTo(replacement.notes());
            assertThat(unchanged.referenceCaseIds()).containsExactly(secondTargetId);

            assertThatThrownBy(() -> repository.replaceAnalyst(cfpb(
                            analystId, TestDatabase.sampleBatch(), "9000008", BASE_TIME)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("ANALYST");
        }
    }

    private static int pragmaInt(TestDatabase db, String pragma) {
        Integer value = db.jdbc().queryForObject("PRAGMA " + pragma, Integer.class);
        return value == null ? -1 : value;
    }

    private static String pragmaString(TestDatabase db, String pragma) {
        return db.jdbc().queryForObject("PRAGMA " + pragma, String.class);
    }

    private static boolean tableExists(TestDatabase db, String table) {
        Long count = db.jdbc().queryForObject(
                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = ?", Long.class, table);
        return count != null && count > 0;
    }

    private static CategoryValues categories() {
        return new CategoryValues(
                "Checking or savings account", null, "Managing an account", null);
    }

    private static CaseRecord analyst(
            UUID id,
            Instant createdAt,
            Instant updatedAt,
            String narrative,
            String handlingNotes,
            List<UUID> references) {
        return new CaseRecord(
                id,
                CaseRecord.Source.ANALYST,
                categories(),
                narrative,
                null,
                "Example Bank",
                null,
                createdAt,
                updatedAt,
                null,
                new CaseRecord.Notes(
                        handlingNotes, CaseRecord.Outcome.UNKNOWN, "Synthetic evaluation"),
                references);
    }

    private static CaseRecord cfpb(UUID id, BatchRecord batch, String complaintId, Instant createdAt) {
        return new CaseRecord(
                id,
                CaseRecord.Source.CFPB,
                categories(),
                "Imported CFPB narrative",
                LocalDate.parse("2024-11-12"),
                "Example Bank",
                "Closed with explanation",
                createdAt,
                createdAt,
                new CaseRecord.Provenance(
                        batch.id(),
                        complaintId,
                        batch.archiveUrl(),
                        batch.sourceFileName(),
                        batch.periodStart(),
                        batch.periodEnd(),
                        batch.csvSha256()),
                null,
                List.of());
    }

    private static CaseRecord cfpbWithNotes(UUID id, BatchRecord batch, String complaintId) {
        CaseRecord base = cfpb(id, batch, complaintId, BASE_TIME);
        return new CaseRecord(
                base.id(),
                base.source(),
                base.categories(),
                base.narrative(),
                base.receivedDate(),
                base.company(),
                base.companyResponse(),
                base.createdAt(),
                base.updatedAt(),
                base.provenance(),
                new CaseRecord.Notes("Forbidden", CaseRecord.Outcome.UNKNOWN, null),
                List.of());
    }

    private static CaseRecord cfpbWithReference(
            UUID id, BatchRecord batch, String complaintId, UUID reference) {
        CaseRecord base = cfpb(id, batch, complaintId, BASE_TIME);
        return new CaseRecord(
                base.id(),
                base.source(),
                base.categories(),
                base.narrative(),
                base.receivedDate(),
                base.company(),
                base.companyResponse(),
                base.createdAt(),
                base.updatedAt(),
                base.provenance(),
                null,
                List.of(reference));
    }

    private static CaseRecord cfpbWithoutProvenance(UUID id) {
        return new CaseRecord(
                id,
                CaseRecord.Source.CFPB,
                categories(),
                "Narrative",
                null,
                null,
                null,
                BASE_TIME,
                BASE_TIME,
                null,
                null,
                List.of());
    }

    private static CaseRecord analystWithProvenance(UUID id, BatchRecord batch) {
        return new CaseRecord(
                id,
                CaseRecord.Source.ANALYST,
                categories(),
                "Narrative",
                null,
                null,
                null,
                BASE_TIME,
                BASE_TIME,
                new CaseRecord.Provenance(
                        batch.id(),
                        "9000009",
                        batch.archiveUrl(),
                        batch.sourceFileName(),
                        batch.periodStart(),
                        batch.periodEnd(),
                        batch.csvSha256()),
                new CaseRecord.Notes("Notes", CaseRecord.Outcome.UNKNOWN, null),
                List.of());
    }
}
