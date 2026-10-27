package id.ac.finresolve.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.ac.finresolve.categories.CategoryValues;
import id.ac.finresolve.cases.CaseRecord;
import id.ac.finresolve.similarity.SimilarityResult;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EvaluationMetricsTest {

    private static final CategoryValues CATEGORIES =
            new CategoryValues("Product", null, "Issue", null);

    @Test
    void precisionAt5UsesAFixedDenominatorAndEmptySlotsAreIrrelevant() {
        EvaluationQuery query = query("Q1", EvaluationQuery.Kind.COMPLAINT);
        EvaluationLabels labels = labels(
                judgment("Q1", "101", true),
                judgment("Q1", "102", false),
                judgment("Q1", "103", true));
        List<SimilarityResult> results = List.of(
                result("101"), result("102"), result("103"));

        EvaluationMetrics.QueryMetric metric =
                EvaluationMetrics.queryMetric(query, results, labels);

        assertThat(metric.returnedCount()).isEqualTo(3);
        assertThat(metric.relevantCount()).isEqualTo(2);
        assertThat(metric.precisionAt5()).isEqualTo(2.0 / 5.0);
        assertThat(metric.returnedPrecision()).isEqualTo(2.0 / 3.0);
        assertThat(metric.hasResults()).isTrue();
    }

    @Test
    void perfectResultsReachOneAndNoResultsHaveZeroReturnedPrecision() {
        EvaluationQuery query = query("Q1", EvaluationQuery.Kind.COMPLAINT);
        EvaluationLabels labels = labels(
                judgment("Q1", "101", true),
                judgment("Q1", "102", true),
                judgment("Q1", "103", true),
                judgment("Q1", "104", true),
                judgment("Q1", "105", true));

        EvaluationMetrics.QueryMetric perfect = EvaluationMetrics.queryMetric(
                query,
                List.of(result("101"), result("102"), result("103"),
                        result("104"), result("105")),
                labels);
        EvaluationMetrics.QueryMetric empty =
                EvaluationMetrics.queryMetric(query, List.of(), labels);

        assertThat(perfect.precisionAt5()).isEqualTo(1.0);
        assertThat(perfect.returnedPrecision()).isEqualTo(1.0);
        assertThat(empty.precisionAt5()).isZero();
        assertThat(empty.returnedPrecision()).isZero();
        assertThat(empty.hasResults()).isFalse();
    }

    @Test
    void requiresAJudgmentForEveryReturnedPair() {
        EvaluationQuery query = query("Q1", EvaluationQuery.Kind.COMPLAINT);

        assertThatThrownBy(() -> EvaluationMetrics.queryMetric(
                        query, List.of(result("101")), labels()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Missing judgment");
    }

    @Test
    void aggregatesComplaintPrecisionAndNegativeFailureProportion() {
        EvaluationQuery complaintOne = query("Q1", EvaluationQuery.Kind.COMPLAINT);
        EvaluationQuery complaintTwo = query("Q2", EvaluationQuery.Kind.COMPLAINT);
        EvaluationQuery negativeOne = query("N1", EvaluationQuery.Kind.NEGATIVE);
        EvaluationQuery negativeTwo = query("N2", EvaluationQuery.Kind.NEGATIVE);
        EvaluationLabels labels = labels(
                judgment("Q1", "101", true),
                judgment("Q1", "102", false),
                judgment("Q2", "201", false),
                judgment("N1", "301", false));

        Map<String, List<SimilarityResult>> results = Map.of(
                "Q1", List.of(result("101"), result("102")),
                "Q2", List.of(result("201")),
                "N1", List.of(result("301")),
                "N2", List.of());

        EvaluationMetrics.Aggregate aggregate = EvaluationMetrics.aggregate(
                List.of(complaintOne, complaintTwo, negativeOne, negativeTwo),
                results,
                labels);

        assertThat(aggregate.complaintQueries()).isEqualTo(2);
        assertThat(aggregate.negativeQueries()).isEqualTo(2);
        assertThat(aggregate.meanPrecisionAt5())
                .isCloseTo((1.0 / 5.0 + 0.0) / 2.0, within());
        assertThat(aggregate.macroPrecision())
                .isCloseTo((0.5 + 0.0) / 2.0, within());
        assertThat(aggregate.microPrecision()).isCloseTo(1.0 / 3.0, within());
        assertThat(aggregate.complaintSuccesses()).isEqualTo(1);
        assertThat(aggregate.complaintSuccessRate()).isCloseTo(0.5, within());
        assertThat(aggregate.complaintReturned()).isEqualTo(3);
        assertThat(aggregate.relevantReturned()).isEqualTo(1);
        assertThat(aggregate.negativeReturned()).isEqualTo(1);
        assertThat(aggregate.totalReturned()).isEqualTo(4);
        assertThat(aggregate.negativeFailures()).isEqualTo(1);
        assertThat(aggregate.negativeFailureRate()).isEqualTo(0.5);
    }

    @Test
    void macroAndMicroPrecisionDifferWhenAComplaintReturnsNothing() {
        EvaluationQuery answered = query("Q1", EvaluationQuery.Kind.COMPLAINT);
        EvaluationQuery empty = query("Q2", EvaluationQuery.Kind.COMPLAINT);
        EvaluationLabels labels = labels(
                judgment("Q1", "101", true),
                judgment("Q2", "201", true));
        Map<String, List<SimilarityResult>> results = Map.of(
                "Q1", List.of(result("101")),
                "Q2", List.of());

        EvaluationMetrics.Aggregate aggregate = EvaluationMetrics.aggregate(
                List.of(answered, empty), results, labels);

        assertThat(aggregate.meanPrecisionAt5()).isCloseTo(0.1, within());
        assertThat(aggregate.macroPrecision()).isCloseTo(0.5, within());
        assertThat(aggregate.microPrecision()).isCloseTo(1.0, within());
        assertThat(aggregate.complaintSuccessRate()).isCloseTo(0.5, within());
        assertThat(aggregate.complaintSuccesses()).isEqualTo(1);
        assertThat(aggregate.complaintReturned()).isEqualTo(1);
        assertThat(aggregate.relevantReturned()).isEqualTo(1);
    }

    private static org.assertj.core.data.Offset<Double> within() {
        return org.assertj.core.data.Offset.offset(1e-12);
    }

    private static EvaluationQuery query(String id, EvaluationQuery.Kind kind) {
        return new EvaluationQuery(id, kind, CATEGORIES, "query narrative", "scenario");
    }

    private static EvaluationLabels labels(EvaluationLabels.Judgment... judgments) {
        return EvaluationLabels.of(List.of(judgments));
    }

    private static EvaluationLabels.Judgment judgment(
            String queryId, String cfpbId, boolean relevant) {
        return new EvaluationLabels.Judgment(
                queryId, cfpbId, relevant, "reason", "case narrative", "reviewer-a");
    }

    private static SimilarityResult result(String cfpbId) {
        return new SimilarityResult(
                caseRecord(cfpbId), 0.0, 0.0, 0.0, List.of(), List.of());
    }

    private static CaseRecord caseRecord(String cfpbId) {
        return new CaseRecord(
                UUID.nameUUIDFromBytes(cfpbId.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                CaseRecord.Source.CFPB,
                CATEGORIES,
                "case narrative",
                null,
                null,
                null,
                Instant.EPOCH,
                Instant.EPOCH,
                new CaseRecord.Provenance(
                        UUID.randomUUID(), cfpbId, "https://example.test/a.zip",
                        "sample.csv", null, null, "checksum"),
                null,
                List.of());
    }
}
