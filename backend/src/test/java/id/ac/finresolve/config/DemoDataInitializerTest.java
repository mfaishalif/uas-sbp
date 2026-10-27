package id.ac.finresolve.config;

import id.ac.finresolve.support.HttpStatusOf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import id.ac.finresolve.domain.cases.CaseRecord;
import id.ac.finresolve.domain.category.CategoryValues;
import id.ac.finresolve.domain.error.BusinessException;
import id.ac.finresolve.repository.importing.ImportRepository;
import id.ac.finresolve.repository.system.SystemRepository;
import id.ac.finresolve.service.importing.ImportService;
import id.ac.finresolve.service.system.DemoReadiness;
import id.ac.finresolve.service.system.HealthService;
import id.ac.finresolve.support.TestDatabase;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;

class DemoDataInitializerTest {

    private static final String HEADER =
            "Complaint ID,Product,Issue,Consumer complaint narrative\n";

    @TempDir
    Path directory;

    private TestDatabase database;
    private ImportService imports;

    @BeforeEach
    void setUp() {
        database = TestDatabase.create(directory);
        imports = new ImportService(
                new ImportRepository(database.database()),
                database.caseRepository(),
                database.database());
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    @Test
    void enabledInitializationImportsTheCommittedSampleWithFiveHundredCases() {
        DemoReadiness readiness = new DemoReadiness(true);
        DemoDataInitializer initializer = initializer(true, committedSample(), readiness);

        assertThat(readiness.isReady()).isFalse();

        ImportService.Result result = initializer.initialize();

        assertThat(readiness.isReady()).isTrue();
        assertThat(result.replayed()).isFalse();
        assertThat(result.batch().processed()).isEqualTo(500);
        assertThat(result.batch().imported()).isEqualTo(500);
        assertThat(result.batch().duplicates()).isZero();
        assertThat(result.batch().rejected()).isZero();
        assertThat(database.caseRepository().count()).isEqualTo(500);
    }

    @Test
    void replayAfterRestartPreservesCfpbIdsAndExistingAnalystCases() {
        DemoDataInitializer first = initializer(true, committedSample(), new DemoReadiness(true));
        ImportService.Result firstResult = first.initialize();
        UUID analystCaseId = insertAnalystCase();
        List<String> cfpbComplaintIds = cfpbComplaintIds();

        DemoReadiness restartReadiness = new DemoReadiness(true);
        DemoDataInitializer restarted =
                initializer(true, committedSample(), restartReadiness);
        ImportService.Result replay = restarted.initialize();

        assertThat(restartReadiness.isReady()).isTrue();
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.batch().id()).isEqualTo(firstResult.batch().id());
        assertThat(database.caseRepository().count()).isEqualTo(501);
        assertThat(database.caseRepository().find(analystCaseId)).isPresent();
        assertThat(cfpbComplaintIds()).containsExactlyElementsOf(cfpbComplaintIds);
        assertThat(cfpbComplaintIds()).hasSize(500);
    }

    @Test
    void disabledInitializationDoesNotReadAMissingSamplePath() {
        DemoReadiness readiness = new DemoReadiness(false);
        DemoDataInitializer initializer =
                initializer(false, directory.resolve("does-not-exist.csv"), readiness);

        ImportService.Result result = initializer.initialize();

        assertThat(result).isNull();
        assertThat(readiness.isReady()).isTrue();
        assertThat(database.caseRepository().count()).isZero();
    }

    @Test
    void enabledInitializationFailsWhenTheSamplePathIsMissing() {
        Path missing = directory.resolve("does-not-exist.csv").toAbsolutePath();
        DemoReadiness readiness = new DemoReadiness(true);
        DemoDataInitializer initializer = initializer(true, missing, readiness);

        assertThatThrownBy(initializer::initialize)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Demo sample")
                .hasMessageContaining(missing.toString())
                .hasCauseInstanceOf(IOException.class);
        assertThat(readiness.isReady()).isFalse();
        assertThat(database.caseRepository().count()).isZero();
    }

    @Test
    void enabledInitializationFailsWhenTheSampleIsInvalid() throws IOException {
        Path invalid = directory.resolve("invalid-sample.csv");
        Files.writeString(invalid, HEADER);
        DemoReadiness readiness = new DemoReadiness(true);
        DemoDataInitializer initializer = initializer(true, invalid, readiness);

        assertThatThrownBy(initializer::initialize)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Demo sample");
        assertThat(readiness.isReady()).isFalse();
        assertThat(database.caseRepository().count()).isZero();
    }

    @Test
    void enabledInitializationFailsWhenTheSampleIsNotTheCommittedFiveHundredCases()
            throws IOException {
        Path small = directory.resolve("small-sample.csv");
        Files.writeString(
                small,
                HEADER + "1,Product A,Issue A,A valid but too small demo narrative\n");
        DemoReadiness readiness = new DemoReadiness(true);
        DemoDataInitializer initializer = initializer(true, small, readiness);

        assertThatThrownBy(initializer::initialize)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("500");
        assertThat(readiness.isReady()).isFalse();
        assertThat(database.caseRepository().count()).isZero();
    }

    @Test
    void aRejectedRowFailsBeforeAnyCasesAreWritten() throws IOException {
        StringBuilder csv = new StringBuilder(HEADER);
        for (int index = 1; index < 500; index++) {
            csv.append(index)
                    .append(",Product A,Issue A,Valid narrative ")
                    .append(index)
                    .append('\n');
        }
        csv.append("500,Product A,Issue A,\n");
        Path sample = directory.resolve("rejected-row-sample.csv");
        Files.writeString(sample, csv);
        DemoReadiness readiness = new DemoReadiness(true);
        DemoDataInitializer initializer = initializer(true, sample, readiness);

        assertThatThrownBy(initializer::initialize)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("500")
                .hasMessageContaining("rejected=1");
        assertThat(readiness.isReady()).isFalse();
        assertThat(database.caseRepository().count()).isZero();
    }

    @Test
    void healthStaysUnavailableUntilDemoInitializationCompletes() {
        DemoReadiness readiness = new DemoReadiness(true);
        HealthService health = new HealthService(
                readiness, new SystemRepository(database.database()));

        BusinessException unavailable = catchThrowableOfType(
                health::check, BusinessException.class);

        assertThat(unavailable).isNotNull();
        assertThat(HttpStatusOf.of(unavailable)).isEqualTo(503);
        assertThat(unavailable.code()).isEqualTo("SERVICE_UNAVAILABLE");

        readiness.markReady();

        health.check();
    }

    private DemoDataInitializer initializer(
            boolean enabled, Path samplePath, DemoReadiness readiness) {
        return new DemoDataInitializer(imports, readiness, enabled, samplePath);
    }

    private UUID insertAnalystCase() {
        Instant now = Instant.parse("2026-10-26T12:00:00Z");
        CaseRecord analystCase = new CaseRecord(
                UUID.randomUUID(),
                CaseRecord.Source.ANALYST,
                new CategoryValues(
                        "Checking or savings account",
                        "Checking account",
                        "Managing an account",
                        null),
                "An existing analyst narrative.",
                null,
                null,
                null,
                now,
                now,
                null,
                new CaseRecord.Notes(
                        "Existing analyst handling notes.",
                        CaseRecord.Outcome.UNKNOWN,
                        null),
                List.of());
        database.caseRepository().insert(analystCase);
        return analystCase.id();
    }

    private List<String> cfpbComplaintIds() {
        return database.caseRepository().snapshot().stream()
                .filter(record -> record.source() == CaseRecord.Source.CFPB)
                .map(record -> record.provenance().complaintId())
                .toList();
    }

    private static Path committedSample() {
        Path fromBackend = Path.of("..", "data", "cfpb", "sample.csv");
        if (Files.isRegularFile(fromBackend)) {
            return fromBackend;
        }
        Path fromRoot = Path.of("data", "cfpb", "sample.csv");
        if (Files.isRegularFile(fromRoot)) {
            return fromRoot;
        }
        throw new AssertionError("Committed sample data/cfpb/sample.csv was not found.");
    }
}
