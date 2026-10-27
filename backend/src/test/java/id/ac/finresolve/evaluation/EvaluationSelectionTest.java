package id.ac.finresolve.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EvaluationSelectionTest {

    private static final EvaluationConfig BASELINE = EvaluationConfig.baseline();

    @Test
    void excludesConfigurationsThatIncreaseNegativeFailures() {
        EvaluationConfig higherPrecisionButNoisier = config(0.2, 0.3);
        EvaluationConfig allowed = config(0.3, 0.4);
        Map<EvaluationConfig, EvaluationMetrics.Aggregate> metrics = metrics(
                row(BASELINE, 0.2, 0.5, 1),
                row(higherPrecisionButNoisier, 0.8, 0.9, 2),
                row(allowed, 0.5, 0.6, 1));

        assertThat(EvaluationSelection.select(
                        List.of(BASELINE, higherPrecisionButNoisier, allowed),
                        BASELINE,
                        metrics))
                .isEqualTo(allowed);
    }

    @Test
    void maximizesPrecisionThenReturnedPrecision() {
        EvaluationConfig first = config(0.3, 0.4);
        EvaluationConfig second = config(0.5, 0.4);
        Map<EvaluationConfig, EvaluationMetrics.Aggregate> metrics = metrics(
                row(BASELINE, 0.2, 0.4, 1),
                row(first, 0.5, 0.4, 1),
                row(second, 0.5, 0.8, 1));

        assertThat(EvaluationSelection.select(
                        List.of(BASELINE, first, second), BASELINE, metrics))
                .isEqualTo(second);
    }

    @Test
    void thenPrefersFewerNegativeFailures() {
        EvaluationConfig moreFailures = config(0.3, 0.4);
        EvaluationConfig fewerFailures = config(0.5, 0.4);
        Map<EvaluationConfig, EvaluationMetrics.Aggregate> metrics = metrics(
                row(BASELINE, 0.2, 0.4, 3),
                row(moreFailures, 0.5, 0.7, 2),
                row(fewerFailures, 0.5, 0.7, 1));

        assertThat(EvaluationSelection.select(
                        List.of(BASELINE, moreFailures, fewerFailures),
                        BASELINE,
                        metrics))
                .isEqualTo(fewerFailures);
    }

    @Test
    void finallyPrefersTheConfigurationClosestToBaseline() {
        EvaluationConfig closer = config(0.5, 0.5);
        EvaluationConfig farther = config(0.3, 0.475);
        Map<EvaluationConfig, EvaluationMetrics.Aggregate> metrics = metrics(
                row(BASELINE, 0.2, 0.4, 3),
                row(closer, 0.5, 0.7, 1),
                row(farther, 0.5, 0.7, 1));

        assertThat(EvaluationSelection.select(
                        List.of(BASELINE, farther, closer), BASELINE, metrics))
                .isEqualTo(closer);
    }

    @Test
    void acceptanceGateRequiresAllThreeConditions() {
        EvaluationMetrics.Aggregate baseline = aggregate(0.2, 0.4, 1);

        assertThat(EvaluationSelection.gate(
                        baseline, aggregate(0.3, 0.4, 1))
                .passed())
                .isTrue();
        assertThat(EvaluationSelection.gate(
                        baseline, aggregate(0.2, 0.5, 0))
                .passed())
                .isFalse();
        assertThat(EvaluationSelection.gate(
                        baseline, aggregate(0.3, 0.3, 1))
                .passed())
                .isFalse();
        assertThat(EvaluationSelection.gate(
                        baseline, aggregate(0.3, 0.5, 2))
                .passed())
                .isFalse();
    }

    @Test
    void gridHasTheDocumentedShapeAndIncludesTheBaseline() {
        List<EvaluationConfig> grid = EvaluationConfig.grid();

        assertThat(grid).hasSize(147);
        assertThat(grid).contains(BASELINE);
        assertThat(grid).allSatisfy(config ->
                assertThat(config.categoryWeight() + config.textWeight()).isEqualTo(1.0));
    }

    private static EvaluationConfig config(double categoryWeight, double threshold) {
        return new EvaluationConfig(categoryWeight, threshold);
    }

    private static Map<EvaluationConfig, EvaluationMetrics.Aggregate> metrics(
            Row... rows) {
        Map<EvaluationConfig, EvaluationMetrics.Aggregate> metrics = new LinkedHashMap<>();
        for (Row row : rows) {
            metrics.put(row.config(), row.aggregate());
        }
        return metrics;
    }

    private static Row row(
            EvaluationConfig config,
            double precisionAt5,
            double returnedPrecision,
            int negativeFailures) {
        return new Row(config, aggregate(precisionAt5, returnedPrecision, negativeFailures));
    }

    private static EvaluationMetrics.Aggregate aggregate(
            double precisionAt5, double returnedPrecision, int negativeFailures) {
        return new EvaluationMetrics.Aggregate(
                10,
                4,
                precisionAt5,
                returnedPrecision,
                20,
                2,
                22,
                negativeFailures,
                negativeFailures / 4.0);
    }

    private record Row(
            EvaluationConfig config, EvaluationMetrics.Aggregate aggregate) {
    }
}
