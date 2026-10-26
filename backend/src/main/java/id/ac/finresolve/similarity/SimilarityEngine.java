package id.ac.finresolve.similarity;

import id.ac.finresolve.cases.CaseRecord;
import id.ac.finresolve.cases.ComplaintInput;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Fits a fresh text snapshot and ranks every case by the configured combined score.
 *
 * <p>No product filter is applied. A candidate needs a positive text score and a combined score at
 * or above the configured threshold. Ordering uses the unrounded score descending and the internal
 * UUID string ascending, then the requested (capped) limit is applied.
 */
public final class SimilarityEngine implements SimilarityScorer {

    private final CategoryScorer categoryScorer;
    private final EnglishTokenizer tokenizer;

    public SimilarityEngine() {
        this(new CategoryScorer(), new EnglishTokenizer());
    }

    public SimilarityEngine(CategoryScorer categoryScorer, EnglishTokenizer tokenizer) {
        this.categoryScorer = Objects.requireNonNull(categoryScorer, "categoryScorer");
        this.tokenizer = Objects.requireNonNull(tokenizer, "tokenizer");
    }

    @Override
    public List<SimilarityResult> rank(
            ComplaintInput query,
            List<CaseRecord> cases,
            int limit,
            SimilaritySettings settings) {
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(cases, "cases");
        Objects.requireNonNull(settings, "settings");
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be at least 1");
        }

        int effectiveLimit = Math.min(limit, settings.maxLimit());
        TfidfSnapshot snapshot = TfidfSnapshot.fit(cases, tokenizer);
        List<SimilarityResult> ranked = new ArrayList<>();
        for (CaseRecord candidate : cases) {
            CategoryScorer.CategoryScore category = categoryScorer.score(
                    query.categories(), candidate.categories(), settings);
            TfidfSnapshot.TextScore text = snapshot.compare(query.narrative(), candidate.id());
            double combined = settings.categoryWeight() * category.score()
                    + settings.textWeight() * text.score();
            combined = Math.max(0.0, Math.min(1.0, combined));
            if (text.score() > 0.0 && combined >= settings.threshold()) {
                ranked.add(new SimilarityResult(
                        candidate,
                        combined,
                        category.score(),
                        text.score(),
                        category.fields(),
                        text.sharedTerms()));
            }
        }

        ranked.sort(Comparator.comparingDouble(SimilarityResult::score)
                .reversed()
                .thenComparing(result -> result.caseRecord().id().toString()));
        int returned = Math.min(effectiveLimit, ranked.size());
        return List.copyOf(ranked.subList(0, returned));
    }
}
