package id.ac.finresolve.similarity;

import id.ac.finresolve.cases.CaseRecord;
import id.ac.finresolve.cases.CaseRepository;
import id.ac.finresolve.cases.ComplaintInput;
import id.ac.finresolve.cases.InputRules;
import id.ac.finresolve.categories.CategoryRepository;
import id.ac.finresolve.categories.CategoryService;
import id.ac.finresolve.categories.CategoryValues;
import id.ac.finresolve.db.Database;
import id.ac.finresolve.http.BusinessException;
import id.ac.finresolve.http.BusinessException.Problem;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Materializes one consistent corpus, releases the database connection, then scores the copy.
 *
 * <p>The query and its TF-IDF vectors are never stored. An empty corpus skips category membership,
 * so an unknown but shape-valid category still returns {@code EMPTY_KNOWLEDGE_BASE}. Every search
 * fits a fresh snapshot, so committed imports and retention are visible on the next request.
 */
@Service
public final class SearchService {

    public static final String MATCHES = "MATCHES";
    public static final String NO_RELEVANT_CASES = "NO_RELEVANT_CASES";
    public static final String EMPTY_KNOWLEDGE_BASE = "EMPTY_KNOWLEDGE_BASE";

    public static final String MATCHES_MESSAGE =
            "Ditemukan preseden yang memenuhi ambang awal.";
    public static final String NO_RELEVANT_CASES_MESSAGE =
            "Tidak ada preseden yang cukup relevan; penilaian tetap dilakukan analis.";
    public static final String EMPTY_KNOWLEDGE_BASE_MESSAGE =
            "Basis kasus masih kosong; impor sampel CFPB terlebih dahulu.";
    public static final String WARNING =
            "Kemiripan dan respons historis bukan bukti keberhasilan penanganan. "
                    + "Kalibrasi eksploratif dan belum divalidasi manusia.";

    private final CaseRepository cases;
    private final CategoryRepository categoryRepository;
    private final CategoryService categoryService;
    private final Database database;
    private final SimilaritySettings settings;
    private final SimilarityScorer scorer;

    @Autowired
    public SearchService(
            CaseRepository cases,
            CategoryRepository categoryRepository,
            CategoryService categoryService,
            Database database,
            SimilaritySettings settings) {
        this(cases, categoryRepository, categoryService, database, settings,
                new SimilarityEngine());
    }

    SearchService(
            CaseRepository cases,
            CategoryRepository categoryRepository,
            CategoryService categoryService,
            Database database,
            SimilaritySettings settings,
            SimilarityScorer scorer) {
        this.cases = Objects.requireNonNull(cases, "cases");
        this.categoryRepository = Objects.requireNonNull(
                categoryRepository, "categoryRepository");
        this.categoryService = Objects.requireNonNull(categoryService, "categoryService");
        this.database = Objects.requireNonNull(database, "database");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.scorer = Objects.requireNonNull(scorer, "scorer");
    }

    public SearchOutcome search(ComplaintInput query, Integer limit) {
        ComplaintInput normalized = InputRules.complaint(query);
        int effectiveLimit = validateLimit(limit);

        Snapshot snapshot = database.read(() -> {
            List<CaseRecord> corpus = cases.snapshot();
            if (corpus.isEmpty()) {
                return new Snapshot(corpus, normalized);
            }
            CategoryValues canonical = categoryService.canonicalize(
                    categoryRepository.list(), normalized.categories());
            return new Snapshot(
                    corpus,
                    new ComplaintInput(
                            canonical,
                            normalized.narrative(),
                            normalized.receivedDate(),
                            normalized.company(),
                            normalized.companyResponse()));
        });

        if (snapshot.corpus().isEmpty()) {
            return new SearchOutcome(
                    EMPTY_KNOWLEDGE_BASE,
                    EMPTY_KNOWLEDGE_BASE_MESSAGE,
                    List.of(),
                    effectiveLimit,
                    0,
                    settings,
                    List.of(WARNING));
        }

        List<SimilarityResult> results = scorer.rank(
                snapshot.query(), snapshot.corpus(), effectiveLimit, settings);
        return new SearchOutcome(
                results.isEmpty() ? NO_RELEVANT_CASES : MATCHES,
                results.isEmpty() ? NO_RELEVANT_CASES_MESSAGE : MATCHES_MESSAGE,
                results,
                effectiveLimit,
                snapshot.corpus().size(),
                settings,
                List.of(WARNING));
    }

    private int validateLimit(Integer limit) {
        int effective = limit == null ? settings.defaultLimit() : limit;
        if (effective < 1 || effective > settings.maxLimit()) {
            throw new BusinessException(
                    400,
                    "VALIDATION_ERROR",
                    "Parameter permintaan tidak valid.",
                    List.of(new Problem(
                            "limit",
                            "OUT_OF_RANGE",
                            "Limit harus antara 1 dan " + settings.maxLimit() + ".")));
        }
        return effective;
    }

    private record Snapshot(List<CaseRecord> corpus, ComplaintInput query) {
    }
}
