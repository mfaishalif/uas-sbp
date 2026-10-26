package id.ac.finresolve.categories;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.ac.finresolve.cases.CaseRecord;
import id.ac.finresolve.cases.CaseRepository;
import id.ac.finresolve.http.BusinessException;
import id.ac.finresolve.imports.BatchRecord;
import id.ac.finresolve.support.TestDatabase;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CategoryServiceTest {

    private static final Instant BASE_TIME = Instant.parse("2026-10-26T10:00:00Z");

    @TempDir
    Path tempDir;

    @Test
    void listsDistinctCfpbTuplesInNullFirstUnicodeOrder() {
        try (TestDatabase db = TestDatabase.create(tempDir)) {
            BatchRecord batch = TestDatabase.sampleBatch();
            db.seedBatch(batch);
            insertCfpb(db, batch, "1001",
                    new CategoryValues("Beta", null, "Issue", null));
            insertCfpb(db, batch, "1002",
                    new CategoryValues("Alpha", "Checking", "Issue", "Subissue"));
            insertCfpb(db, batch, "1003",
                    new CategoryValues("Alpha", null, "Issue", null));
            insertCfpb(db, batch, "1004",
                    new CategoryValues("Alpha", "Checking", "Issue", null));
            insertCfpb(db, batch, "1005",
                    new CategoryValues("Alpha", null, "Issue", null));
            insertAnalyst(db, new CategoryValues("Analyst only", null, "Issue", null));

            CategoryRepository repository = new CategoryRepository(db.database());
            List<CategoryValues> first = repository.list();
            List<CategoryValues> second = repository.list();

            assertThat(first).containsExactly(
                    new CategoryValues("Alpha", null, "Issue", null),
                    new CategoryValues("Alpha", "Checking", "Issue", null),
                    new CategoryValues("Alpha", "Checking", "Issue", "Subissue"),
                    new CategoryValues("Beta", null, "Issue", null));
            assertThat(second).containsExactlyElementsOf(first);
            assertThat(first).noneMatch(tuple -> "Analyst only".equals(tuple.product()));
        }
    }

    @Test
    void canonicalizesCaseAndSpacingVariantsToTheFirstSourceLabel() {
        try (TestDatabase db = TestDatabase.create(tempDir)) {
            BatchRecord batch = TestDatabase.sampleBatch();
            db.seedBatch(batch);
            insertCfpb(db, batch, "2001",
                    new CategoryValues(
                            "Checking account", "Checking account",
                            "Managing an account", "Closing an account"));
            insertCfpb(db, batch, "2002",
                    new CategoryValues(
                            "checking   account", "checking   account",
                            "managing an ACCOUNT", "closing AN account"));

            CategoryService service = new CategoryService(new CategoryRepository(db.database()));

            assertThat(service.canonicalize(new CategoryValues(
                            "  CHECKING\tACCOUNT  ",
                            " checking account ",
                            " managing   an account ",
                            " closing an account ")))
                    .isEqualTo(new CategoryValues(
                            "Checking account", "Checking account",
                            "Managing an account", "Closing an account"));
        }
    }

    @Test
    void independentOptionalBranchesAreValid() {
        try (TestDatabase db = TestDatabase.create(tempDir)) {
            BatchRecord batch = TestDatabase.sampleBatch();
            db.seedBatch(batch);
            insertCfpb(db, batch, "3001",
                    new CategoryValues("P", "A", "I", "X"));
            insertCfpb(db, batch, "3002",
                    new CategoryValues("P", "B", "I", "Y"));
            insertCfpb(db, batch, "3003",
                    new CategoryValues("Q", "C", "I", "X"));
            insertCfpb(db, batch, "3004",
                    new CategoryValues("Q", "A", "J", "Z"));

            CategoryService service = new CategoryService(new CategoryRepository(db.database()));

            assertThat(service.canonicalize(new CategoryValues("p", "a", "i", "y")))
                    .isEqualTo(new CategoryValues("P", "A", "I", "Y"));
            assertThat(service.canonicalize(new CategoryValues("P", null, "I", null)))
                    .isEqualTo(new CategoryValues("P", null, "I", null));

            assertInvalid(service, new CategoryValues("P", "C", "I", null), "subProduct");
            assertInvalid(service, new CategoryValues("P", null, "J", null), "issue");
            assertInvalid(service, new CategoryValues("P", "A", "I", "Z"), "subIssue");
            assertInvalid(service, new CategoryValues("Q", "C", "K", null), "issue");
        }
    }

    @Test
    void listsCfpbTuplesInsideTheCallersTransaction() {
        try (TestDatabase db = TestDatabase.create(tempDir)) {
            BatchRecord batch = TestDatabase.sampleBatch();
            db.seedBatch(batch);
            CategoryRepository repository = new CategoryRepository(db.database());

            db.database().write(() -> {
                insertCfpb(db, batch, "5001",
                        new CategoryValues("P", "A", "I", "X"));

                assertThat(repository.list())
                        .containsExactly(new CategoryValues("P", "A", "I", "X"));
                return null;
            });
        }
    }

    @Test
    void emptyCatalogIsATypedConflict() {
        try (TestDatabase db = TestDatabase.create(tempDir)) {
            CategoryService service = new CategoryService(new CategoryRepository(db.database()));

            assertThatThrownBy(() -> service.canonicalize(
                            new CategoryValues("Checking account", null, "Managing an account", null)))
                    .isInstanceOfSatisfying(BusinessException.class, failure -> {
                        assertThat(failure.status()).isEqualTo(409);
                        assertThat(failure.code()).isEqualTo("EMPTY_CATEGORY_CATALOG");
                        assertThat(failure.fieldErrors()).isEmpty();
                    });
        }
    }

    @Test
    void analystCasesNeverExpandTheCatalog() {
        try (TestDatabase db = TestDatabase.create(tempDir)) {
            insertAnalyst(db, new CategoryValues("Analyst product", null, "Issue", null));
            CategoryService service = new CategoryService(new CategoryRepository(db.database()));

            assertThat(new CategoryRepository(db.database()).list()).isEmpty();
            assertThatThrownBy(() -> service.canonicalize(
                            new CategoryValues("Analyst product", null, "Issue", null)))
                    .isInstanceOfSatisfying(BusinessException.class, failure -> {
                        assertThat(failure.status()).isEqualTo(409);
                        assertThat(failure.code()).isEqualTo("EMPTY_CATEGORY_CATALOG");
                    });
        }
    }

    @Test
    void reportsSafeIndonesianFieldErrorsWithoutNarrativeText() {
        try (TestDatabase db = TestDatabase.create(tempDir)) {
            BatchRecord batch = TestDatabase.sampleBatch();
            db.seedBatch(batch);
            insertCfpb(db, batch, "4001",
                    new CategoryValues("P", "A", "I", "X"));
            CategoryService service = new CategoryService(new CategoryRepository(db.database()));

            BusinessException failure = failureFor(
                    service, new CategoryValues("P", "missing", "I", "missing"));

            assertThat(failure.status()).isEqualTo(400);
            assertThat(failure.code()).isEqualTo("INVALID_CATEGORY");
            assertThat(failure.fieldErrors())
                    .extracting(BusinessException.Problem::field)
                    .containsExactly("subProduct", "subIssue");
            assertThat(failure.fieldErrors())
                    .allSatisfy(problem -> assertThat(problem.message())
                            .startsWith("Nilai ")
                            .doesNotContainIgnoringCase("narrative"));
        }
    }

    private void assertInvalid(CategoryService service, CategoryValues values, String field) {
        BusinessException failure = failureFor(service, values);
        assertThat(failure.status()).isEqualTo(400);
        assertThat(failure.code()).isEqualTo("INVALID_CATEGORY");
        assertThat(failure.fieldErrors())
                .extracting(BusinessException.Problem::field)
                .contains(field);
    }

    private BusinessException failureFor(CategoryService service, CategoryValues values) {
        try {
            service.canonicalize(values);
        } catch (BusinessException failure) {
            return failure;
        }
        throw new AssertionError("Expected a BusinessException");
    }

    private static void insertCfpb(
            TestDatabase db, BatchRecord batch, String complaintId, CategoryValues categories) {
        db.caseRepository().insert(new CaseRecord(
                UUID.randomUUID(),
                CaseRecord.Source.CFPB,
                categories,
                "Imported CFPB narrative for catalog " + complaintId,
                null,
                "Example Bank",
                null,
                BASE_TIME,
                BASE_TIME,
                new CaseRecord.Provenance(
                        batch.id(),
                        complaintId,
                        batch.archiveUrl(),
                        batch.sourceFileName(),
                        batch.periodStart(),
                        batch.periodEnd(),
                        batch.csvSha256()),
                null,
                List.of()));
    }

    private static void insertAnalyst(TestDatabase db, CategoryValues categories) {
        db.caseRepository().insert(new CaseRecord(
                UUID.randomUUID(),
                CaseRecord.Source.ANALYST,
                categories,
                "Analyst narrative",
                null,
                "Example Bank",
                null,
                BASE_TIME,
                BASE_TIME,
                null,
                new CaseRecord.Notes(
                        "Reviewed the complaint.", CaseRecord.Outcome.UNKNOWN, null),
                List.of()));
    }
}
