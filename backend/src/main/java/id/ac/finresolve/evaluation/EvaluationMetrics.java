package id.ac.finresolve.evaluation;

import id.ac.finresolve.similarity.SimilarityResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Precision-oriented metrics for the offline query set. Recall is deliberately not reported. */
public final class EvaluationMetrics {

    public static final int PRECISION_DENOMINATOR = 5;

    private EvaluationMetrics() {
    }

    public static QueryMetric queryMetric(
            EvaluationQuery query,
            List<SimilarityResult> results,
            EvaluationLabels labels) {
        List<String> ids = new ArrayList<>();
        int relevant = 0;
        int counted = 0;
        for (SimilarityResult result : results) {
            if (counted >= PRECISION_DENOMINATOR) {
                break;
            }
            counted++;
            String cfpbId = EvaluationCaseIds.complaintId(result.caseRecord());
            ids.add(cfpbId);
            if (labels.relevant(query.queryId(), cfpbId)) {
                relevant++;
            }
        }
        int returnedCount = ids.size();
        double precisionAt5 = relevant / (double) PRECISION_DENOMINATOR;
        double returnedPrecision = returnedCount == 0
                ? 0.0
                : relevant / (double) returnedCount;
        return new QueryMetric(
                query.queryId(),
                query.kind(),
                returnedCount,
                relevant,
                precisionAt5,
                returnedPrecision,
                returnedCount > 0,
                List.copyOf(ids));
    }

    public static Aggregate aggregate(
            List<EvaluationQuery> queries,
            Map<String, List<SimilarityResult>> resultsByQuery,
            EvaluationLabels labels) {
        int complaints = 0;
        int negatives = 0;
        double precisionSum = 0.0;
        double returnedPrecisionSum = 0.0;
        int complaintReturned = 0;
        int negativeReturned = 0;
        int negativeFailures = 0;
        for (EvaluationQuery query : queries) {
            List<SimilarityResult> results = resultsByQuery.get(query.queryId());
            if (results == null) {
                throw new IllegalArgumentException(
                        "Missing results for query: " + query.queryId());
            }
            QueryMetric metric = queryMetric(query, results, labels);
            if (query.kind() == EvaluationQuery.Kind.COMPLAINT) {
                complaints++;
                precisionSum += metric.precisionAt5();
                returnedPrecisionSum += metric.returnedPrecision();
                complaintReturned += metric.returnedCount();
            } else {
                negatives++;
                negativeReturned += metric.returnedCount();
                if (metric.hasResults()) {
                    negativeFailures++;
                }
            }
        }
        double meanPrecisionAt5 = complaints == 0 ? 0.0 : precisionSum / complaints;
        double meanReturnedPrecision = complaints == 0
                ? 0.0
                : returnedPrecisionSum / complaints;
        double negativeFailureRate = negatives == 0
                ? 0.0
                : negativeFailures / (double) negatives;
        return new Aggregate(
                complaints,
                negatives,
                meanPrecisionAt5,
                meanReturnedPrecision,
                complaintReturned,
                negativeReturned,
                complaintReturned + negativeReturned,
                negativeFailures,
                negativeFailureRate);
    }

    public record QueryMetric(
            String queryId,
            EvaluationQuery.Kind kind,
            int returnedCount,
            int relevantCount,
            double precisionAt5,
            double returnedPrecision,
            boolean hasResults,
            List<String> returnedCfpbIds) {

        public QueryMetric {
            returnedCfpbIds = List.copyOf(returnedCfpbIds == null ? List.of() : returnedCfpbIds);
        }
    }

    public record Aggregate(
            int complaintQueries,
            int negativeQueries,
            double meanPrecisionAt5,
            double meanReturnedPrecision,
            int complaintReturned,
            int negativeReturned,
            int totalReturned,
            int negativeFailures,
            double negativeFailureRate) {
    }
}
