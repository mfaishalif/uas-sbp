package id.ac.finresolve.evaluation;

import id.ac.finresolve.similarity.EnglishTokenizer;
import id.ac.finresolve.similarity.SimilarityEngine;
import id.ac.finresolve.similarity.SimilarityMode;
import id.ac.finresolve.similarity.SimilarityResult;
import id.ac.finresolve.similarity.TfidfSnapshot;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs the production scorer over the offline corpus.
 *
 * <p>One TF-IDF snapshot is fitted per similarity mode for the whole run, then reused for every
 * query/config pair. Ranking, thresholding, and ordering remain those of
 * {@link SimilarityEngine}.
 */
public final class EvaluationRunner {

    private final EvaluationCorpus corpus;
    private final EvaluationConfig.EvaluationGrid grid;
    private final SimilarityEngine engine;
    private final Map<SimilarityMode, TfidfSnapshot> snapshots;

    public EvaluationRunner(EvaluationCorpus corpus, EvaluationConfig.EvaluationGrid grid) {
        this.corpus = corpus;
        this.grid = grid;
        this.engine = new SimilarityEngine();
        Map<SimilarityMode, TfidfSnapshot> fitted = new EnumMap<>(SimilarityMode.class);
        for (SimilarityMode mode : grid.modes()) {
            fitted.put(
                    mode,
                    TfidfSnapshot.fit(corpus.cases(), new EnglishTokenizer(mode)));
        }
        this.snapshots = Map.copyOf(fitted);
    }

    public List<SimilarityResult> rank(EvaluationQuery query, EvaluationConfig config) {
        TfidfSnapshot snapshot = snapshots.get(config.mode());
        if (snapshot == null) {
            throw new IllegalArgumentException(
                    "No snapshot fitted for mode: " + config.mode().configValue());
        }
        return engine.rank(
                queryInput(query),
                corpus.cases(),
                snapshot,
                grid.limit(),
                config.settings(grid));
    }

    public Map<String, List<SimilarityResult>> rank(
            List<EvaluationQuery> queries, EvaluationConfig config) {
        Map<String, List<SimilarityResult>> results = new LinkedHashMap<>();
        for (EvaluationQuery query : queries) {
            results.put(query.queryId(), rank(query, config));
        }
        return Map.copyOf(results);
    }

    public Map<EvaluationConfig, Map<String, List<SimilarityResult>>> rankAll(
            List<EvaluationQuery> queries, List<EvaluationConfig> configs) {
        Map<EvaluationConfig, Map<String, List<SimilarityResult>>> all =
                new LinkedHashMap<>();
        for (EvaluationConfig config : configs) {
            all.put(config, rank(queries, config));
        }
        return all;
    }

    public List<String> returnedComplaintIds(List<SimilarityResult> results) {
        List<String> ids = new ArrayList<>(results.size());
        for (SimilarityResult result : results) {
            ids.add(EvaluationCaseIds.complaintId(result.caseRecord()));
        }
        return List.copyOf(ids);
    }

    public EvaluationCorpus corpus() {
        return corpus;
    }

    public EvaluationConfig.EvaluationGrid grid() {
        return grid;
    }

    static id.ac.finresolve.cases.ComplaintInput queryInput(EvaluationQuery query) {
        return new id.ac.finresolve.cases.ComplaintInput(
                query.categories(), query.narrative(), null, null, null);
    }
}
