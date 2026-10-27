package id.ac.finresolve.evaluation;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Calibration-only configuration selection and the final-test acceptance gate. */
public final class EvaluationSelection {

    private static final double EPSILON = 1e-12;

    private EvaluationSelection() {
    }

    /**
     * Selects from calibration aggregates only. Negative queries with any result must not exceed
     * the baseline; then maximize mean precision@5, mean returned precision, fewer negative
     * failures, and finally distance from the baseline grid point.
     */
    public static EvaluationConfig select(
            List<EvaluationConfig> configs,
            EvaluationConfig baseline,
            Map<EvaluationConfig, EvaluationMetrics.Aggregate> calibration) {
        EvaluationMetrics.Aggregate baselineAggregate = calibration.get(baseline);
        if (baselineAggregate == null) {
            throw new IllegalArgumentException("Baseline is missing from calibration metrics");
        }
        Comparator<EvaluationConfig> comparator =
                Comparator.comparingDouble(
                                (EvaluationConfig config) -> calibration.get(config)
                                        .meanPrecisionAt5())
                        .reversed()
                        .thenComparing(Comparator.comparingDouble(
                                        (EvaluationConfig config) -> calibration.get(config)
                                                .meanReturnedPrecision())
                                .reversed())
                        .thenComparingInt(config -> calibration.get(config).negativeFailures())
                        .thenComparingInt(EvaluationConfig::distanceToBaselineInGridSteps)
                        .thenComparingDouble(EvaluationConfig::categoryWeight)
                        .thenComparingDouble(EvaluationConfig::threshold);

        return configs.stream()
                .filter(config -> calibration.get(config).negativeFailures()
                        <= baselineAggregate.negativeFailures())
                .min(comparator)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No configuration satisfies the negative-query constraint"));
    }

    public static Gate gate(
            EvaluationMetrics.Aggregate baseline,
            EvaluationMetrics.Aggregate selected) {
        boolean precisionAt5Increased =
                selected.meanPrecisionAt5() > baseline.meanPrecisionAt5() + EPSILON;
        boolean returnedPrecisionNotLower =
                selected.meanReturnedPrecision() + EPSILON >= baseline.meanReturnedPrecision();
        boolean negativeFailuresNotIncreased =
                selected.negativeFailures() <= baseline.negativeFailures();
        return new Gate(
                precisionAt5Increased,
                returnedPrecisionNotLower,
                negativeFailuresNotIncreased,
                precisionAt5Increased && returnedPrecisionNotLower
                        && negativeFailuresNotIncreased);
    }

    public record Gate(
            boolean precisionAt5Increased,
            boolean returnedPrecisionNotLower,
            boolean negativeFailuresNotIncreased,
            boolean passed) {
    }
}
