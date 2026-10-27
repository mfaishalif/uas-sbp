package id.ac.finresolve.service.search;

import id.ac.finresolve.domain.similarity.SimilarityEngine;
import id.ac.finresolve.domain.similarity.SimilarityResult;
import static org.assertj.core.api.Assertions.assertThat;

import id.ac.finresolve.domain.cases.CaseRecord;
import id.ac.finresolve.repository.cases.CaseRepository;
import id.ac.finresolve.domain.cases.ComplaintInput;
import id.ac.finresolve.repository.category.CategoryRepository;
import id.ac.finresolve.service.category.CategoryService;
import id.ac.finresolve.domain.category.CategoryValues;
import id.ac.finresolve.domain.similarity.SimilarityScorer;
import id.ac.finresolve.domain.similarity.SimilaritySettings;
import id.ac.finresolve.support.TestDatabase;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SearchSnapshotTest {

    private static final CategoryValues CHECKING = new CategoryValues(
            "Checking or savings account", "Checking account", "Managing an account", null);

    @TempDir
    private Path tempDirectory;

    private TestDatabase database;
    private UUID batchId;
    private final AtomicLong complaintIds = new AtomicLong(900_000_000L);

    @BeforeEach
    void setUp() {
        database = TestDatabase.create(tempDirectory);
        batchId = database.seedBatch(TestDatabase.sampleBatch());
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    @Test
    void calculatesAfterReleasingTheConnectionWithoutMixingLaterWrites() throws Exception {
        UUID firstId = seedCfpb("A transfer is missing from my checking account.");
        BlockingScorer scorer = new BlockingScorer();
        SearchService service = searchService(SimilaritySettings.defaults(), scorer);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<SearchOutcome> search =
                    executor.submit(() -> service.search(query(), null));
            assertThat(scorer.entered.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(scorer.seenCaseIds).containsExactly(firstId);

            Future<UUID> committedRead = executor.submit(() -> database.caseRepository()
                    .find(firstId)
                    .orElseThrow()
                    .id());
            assertThat(committedRead.get(5, TimeUnit.SECONDS)).isEqualTo(firstId);

            Future<UUID> committedWrite =
                    executor.submit(() -> seedCfpb("A second transfer is missing."));
            UUID secondId = committedWrite.get(5, TimeUnit.SECONDS);

            scorer.release.countDown();
            SearchOutcome first = search.get(10, TimeUnit.SECONDS);
            assertThat(first.corpusSize()).isEqualTo(1);
            assertThat(first.results())
                    .extracting(result -> result.caseRecord().id())
                    .containsExactly(firstId)
                    .doesNotContain(secondId);

            SearchOutcome next = searchService(
                    SimilaritySettings.defaults(), new SimilarityEngine())
                    .search(query(), null);
            assertThat(next.corpusSize()).isEqualTo(2);
            assertThat(next.results())
                    .extracting(result -> result.caseRecord().id())
                    .contains(firstId, secondId);
        } finally {
            scorer.release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void outcomeCarriesTheActualConfiguredWeightsAndDefaultLimit() {
        seedCfpb("A transfer is missing from my checking account.");
        SimilaritySettings custom = new SimilaritySettings(
                0.7, 0.3, 0.4, 0.1, 0.4, 0.1, 0.0, 3, 7);

        SearchOutcome outcome = searchService(custom, new SimilarityEngine())
                .search(query(), null);

        assertThat(outcome.limit()).isEqualTo(3);
        assertThat(outcome.configuration()).isSameAs(custom);
        assertThat(outcome.status()).isEqualTo(SearchService.MATCHES);
        assertThat(outcome.results()).hasSize(1);
    }

    @Test
    void emptyCorpusSkipsCategoryMembershipAndReturnsEmptyStatus() {
        SearchOutcome outcome = searchService(
                SimilaritySettings.defaults(), new SimilarityEngine())
                .search(new ComplaintInput(
                        new CategoryValues("Unknown product", null, "Unknown issue", null),
                        "A missing transfer narrative.",
                        null,
                        null,
                        null), null);

        assertThat(outcome.status()).isEqualTo(SearchService.EMPTY_KNOWLEDGE_BASE);
        assertThat(outcome.corpusSize()).isZero();
        assertThat(outcome.results()).isEmpty();
    }

    private SearchService searchService(SimilaritySettings settings, SimilarityScorer scorer) {
        return new SearchService(
                database.caseRepository(),
                new CategoryRepository(database.database()),
                new CategoryService(new CategoryRepository(database.database())),
                database.database(),
                settings,
                scorer);
    }

    private ComplaintInput query() {
        return new ComplaintInput(
                CHECKING,
                "My transfer is missing from my checking account.",
                null,
                null,
                null);
    }

    private UUID seedCfpb(String narrative) {
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
                        String.valueOf(complaintIds.getAndIncrement()),
                        "https://files.consumerfinance.gov/f/documents/synthetic-archive.zip",
                        "finresolve-sample.csv",
                        LocalDate.parse("2024-11-01"),
                        LocalDate.parse("2024-12-31"),
                        "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"),
                null,
                List.of()));
        return id;
    }

    private static final class BlockingScorer implements SimilarityScorer {

        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final SimilarityEngine delegate = new SimilarityEngine();
        private volatile List<UUID> seenCaseIds = List.of();

        @Override
        public List<SimilarityResult> rank(
                ComplaintInput query,
                List<CaseRecord> cases,
                int limit,
                SimilaritySettings settings) {
            seenCaseIds = cases.stream().map(CaseRecord::id).toList();
            entered.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out waiting to resume scoring.");
                }
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting to resume scoring.", failure);
            }
            return delegate.rank(query, cases, limit, settings);
        }
    }
}
