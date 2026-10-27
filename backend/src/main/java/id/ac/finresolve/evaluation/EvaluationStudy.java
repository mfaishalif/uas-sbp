package id.ac.finresolve.evaluation;

import id.ac.finresolve.evaluation.EvaluationCandidates.EvaluationCandidate;

import id.ac.finresolve.similarity.SimilarityResult;
import java.util.List;
import java.util.Map;

/** All material needed to write the committed evaluation artifacts. */
public record EvaluationStudy(
        List<EvaluationQuery> queries,
        EvaluationSplit split,
        EvaluationConfig baseline,
        EvaluationConfig selected,
        Map<EvaluationConfig, EvaluationMetrics.Aggregate> calibrationAggregates,
        Map<String, List<SimilarityResult>> calibrationBaselineResults,
        Map<String, List<SimilarityResult>> calibrationSelectedResults,
        Map<String, List<SimilarityResult>> testBaselineResults,
        Map<String, List<SimilarityResult>> testSelectedResults,
        EvaluationLabels labels,
        List<EvaluationCandidate> candidates,
        String sampleSha256,
        int sampleRecordCount) {

    public EvaluationStudy {
        queries = List.copyOf(queries);
        calibrationAggregates = Map.copyOf(calibrationAggregates);
        calibrationBaselineResults = Map.copyOf(calibrationBaselineResults);
        calibrationSelectedResults = Map.copyOf(calibrationSelectedResults);
        testBaselineResults = Map.copyOf(testBaselineResults);
        testSelectedResults = Map.copyOf(testSelectedResults);
        candidates = List.copyOf(candidates);
    }
}
