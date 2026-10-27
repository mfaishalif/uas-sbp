package id.ac.finresolve.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import id.ac.finresolve.similarity.SimilarityMode;
import java.nio.file.Path;
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
                row(BASELINE, 0.2, 0.5, 0.5, 0.5, 1),
                row(higherPrecisionButNoisier, 0.8, 0.9, 0.9, 0.9, 2),
                row(allowed, 0.5, 0.6, 0.6, 0.6, 1));

        EvaluationSelection.Selection selection = EvaluationSelection.select(
                List.of(BASELINE, higherPrecisionButNoisier, allowed), BASELINE, metrics);

        assertThat(selection.selected()).isEqualTo(allowed);
        assertThat(selection.precisionImproved()).isTrue();
    }

    @Test
    void excludesMacroMicroAndPerQuerySuccessRegressions() {
        EvaluationConfig macroRegression = config(0.3, 0.4);
        EvaluationConfig microRegression = config(0.5, 0.4);
        EvaluationConfig successRegression = config(0.6, 0.4);
        EvaluationConfig eligible = config(0.7, 0.4);
        Map<EvaluationConfig, EvaluationMetrics.Aggregate> metrics = metrics(
                row(BASELINE, 0.2, 0.5, 0.5, 0.5, 1),
                row(macroRegression, 0.8, 0.4, 0.9, 0.9, 1),
                row(microRegression, 0.8, 0.9, 0.4, 0.9, 1),
                row(successRegression, 0.8, 0.9, 0.9, 0.4, 1),
                row(eligible, 0.5, 0.6, 0.6, 0.6, 1));

        assertThat(EvaluationSelection.select(
                        List.of(BASELINE, macroRegression, microRegression,
                                successRegression, eligible),
                        BASELINE,
                        metrics)
                .selected())
                .isEqualTo(eligible);
    }

    @Test
    void maximizesPrecisionThenMicroPrecision() {
        EvaluationConfig first = config(0.3, 0.4);
        EvaluationConfig second = config(0.5, 0.4);
        Map<EvaluationConfig, EvaluationMetrics.Aggregate> metrics = metrics(
                row(BASELINE, 0.2, 0.5, 0.5, 0.5, 1),
                row(first, 0.5, 0.6, 0.6, 0.6, 1),
                row(second, 0.5, 0.6, 0.8, 0.6, 1));

        assertThat(EvaluationSelection.select(
                        List.of(BASELINE, first, second), BASELINE, metrics)
                .selected())
                .isEqualTo(second);
    }

    @Test
    void thenPrefersPerQuerySuccessThenFewerNegativeFailures() {
        EvaluationConfig lowerSuccess = config(0.3, 0.4);
        EvaluationConfig higherSuccess = config(0.5, 0.4);
        EvaluationConfig fewerFailures = config(0.6, 0.4);
        Map<EvaluationConfig, EvaluationMetrics.Aggregate> metrics = metrics(
                row(BASELINE, 0.2, 0.5, 0.5, 0.5, 3),
                row(lowerSuccess, 0.5, 0.7, 0.7, 0.6, 2),
                row(higherSuccess, 0.5, 0.7, 0.7, 0.8, 2),
                row(fewerFailures, 0.5, 0.7, 0.7, 0.8, 1));

        assertThat(EvaluationSelection.select(
                        List.of(BASELINE, lowerSuccess, higherSuccess, fewerFailures),
                        BASELINE,
                        metrics)
                .selected())
                .isEqualTo(fewerFailures);
    }

    @Test
    void treatsDifferencesWithinNumericToleranceAsATie() {
        EvaluationConfig lower = config(0.3, 0.4);
        EvaluationConfig higherByTinyAmount = config(0.5, 0.4);
        Map<EvaluationConfig, EvaluationMetrics.Aggregate> metrics = metrics(
                row(BASELINE, 0.2, 0.5, 0.5, 0.5, 1),
                row(lower, 0.5, 0.6, 0.6, 0.6, 1),
                row(higherByTinyAmount, 0.5 + 1e-13, 0.6, 0.6, 0.6, 1));

        assertThat(EvaluationSelection.select(
                        List.of(BASELINE, higherByTinyAmount, lower), BASELINE, metrics)
                .selected())
                .isEqualTo(lower);
    }

    @Test
    void thenPrefersBaselineModeThenClosestGridPoint() {
        EvaluationConfig phrasesSame = new EvaluationConfig(
                SimilarityMode.PHRASES, 0.5, 0.5);
        EvaluationConfig baselineSame = new EvaluationConfig(
                SimilarityMode.BASELINE, 0.5, 0.5);
        EvaluationConfig closer = new EvaluationConfig(
                SimilarityMode.BASELINE, 0.5, 0.5);
        EvaluationConfig farther = new EvaluationConfig(
                SimilarityMode.BASELINE, 0.3, 0.475);
        Map<EvaluationConfig, EvaluationMetrics.Aggregate> metrics = metrics(
                row(BASELINE, 0.2, 0.5, 0.5, 0.5, 3),
                row(phrasesSame, 0.5, 0.7, 0.7, 0.7, 1),
                row(baselineSame, 0.5, 0.7, 0.7, 0.7, 1),
                row(farther, 0.5, 0.7, 0.7, 0.7, 1));

        assertThat(EvaluationSelection.select(
                        List.of(BASELINE, phrasesSame, baselineSame, farther),
                        BASELINE,
                        metrics)
                .selected())
                .isEqualTo(closer);
    }

    @Test
    void keepsBaselineWhenNoEligibleCandidateImprovesPrecisionAt5() {
        EvaluationConfig samePrecision = config(0.5, 0.5);
        Map<EvaluationConfig, EvaluationMetrics.Aggregate> metrics = metrics(
                row(BASELINE, 0.2, 0.5, 0.5, 0.5, 1),
                row(samePrecision, 0.2, 0.7, 0.7, 0.7, 0));

        EvaluationSelection.Selection selection = EvaluationSelection.select(
                List.of(BASELINE, samePrecision), BASELINE, metrics);

        assertThat(selection.precisionImproved()).isFalse();
        assertThat(selection.selected()).isEqualTo(BASELINE);
    }

    @Test
    void acceptanceGateRequiresAllFiveConditions() {
        EvaluationMetrics.Aggregate baseline = aggregate(0.2, 0.5, 0.5, 0.5, 1);

        assertThat(EvaluationSelection.gate(
                        baseline, aggregate(0.3, 0.6, 0.6, 0.6, 1))
                .passed())
                .isTrue();
        assertThat(EvaluationSelection.gate(
                        baseline, aggregate(0.2, 0.6, 0.6, 0.6, 1))
                .passed())
                .isFalse();
        assertThat(EvaluationSelection.gate(
                        baseline, aggregate(0.3, 0.4, 0.6, 0.6, 1))
                .passed())
                .isFalse();
        assertThat(EvaluationSelection.gate(
                        baseline, aggregate(0.3, 0.6, 0.4, 0.6, 1))
                .passed())
                .isFalse();
        assertThat(EvaluationSelection.gate(
                        baseline, aggregate(0.3, 0.6, 0.6, 0.4, 1))
                .passed())
                .isFalse();
        assertThat(EvaluationSelection.gate(
                        baseline, aggregate(0.3, 0.6, 0.6, 0.6, 2))
                .passed())
                .isFalse();
    }

    @Test
    void manifestGridHasTwoModesAndTheBaselinePoint() {
        EvaluationManifest manifest = EvaluationManifest.load(
                Path.of("../docs/evaluation/v2/manifest.json"));
        List<EvaluationConfig> grid = EvaluationConfig.grid(manifest.grid());

        assertThat(grid).hasSize(294);
        assertThat(grid).contains(BASELINE);
        assertThat(grid.stream()
                .filter(config -> config.mode() == SimilarityMode.BASELINE))
                .hasSize(147);
        assertThat(grid.stream()
                .filter(config -> config.mode() == SimilarityMode.PHRASES))
                .hasSize(147);
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
            double macroPrecision,
            double microPrecision,
            double successRate,
            int negativeFailures) {
        return new Row(
                config,
                aggregate(
                        precisionAt5, macroPrecision, microPrecision,
                        successRate, negativeFailures));
    }

    private static EvaluationMetrics.Aggregate aggregate(
            double precisionAt5,
            double macroPrecision,
            double microPrecision,
            double successRate,
            int negativeFailures) {
        int complaintReturned = 100;
        int relevantReturned = (int) Math.round(microPrecision * complaintReturned);
        int complaintSuccesses = (int) Math.round(successRate * 40);
        return new EvaluationMetrics.Aggregate(
                40,
                24,
                precisionAt5,
                macroPrecision,
                microPrecision,
                complaintSuccesses,
                successRate,
                complaintReturned,
                relevantReturned,
                10,
                complaintReturned + 10,
                negativeFailures,
                negativeFailures / 24.0);
    }

    private record Row(
            EvaluationConfig config, EvaluationMetrics.Aggregate aggregate) {
    }
}
