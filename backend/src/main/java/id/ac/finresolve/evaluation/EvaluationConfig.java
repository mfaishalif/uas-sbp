package id.ac.finresolve.evaluation;

import id.ac.finresolve.similarity.SimilaritySettings;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One point on the fixed calibration grid.
 *
 * <p>Category weight runs 0.1 to 0.7 by 0.1 and threshold runs 0.20 to 0.70 by 0.025. Text weight
 * is always the complement. Field weights, text-overlap requirement, limit, and algorithm version
 * are not varied.
 */
public record EvaluationConfig(double categoryWeight, double threshold) {

    public static final double BASELINE_CATEGORY_WEIGHT = 0.4;
    public static final double BASELINE_THRESHOLD = 0.5;
    private static final int WEIGHT_STEPS = 7;
    private static final int THRESHOLD_STEPS = 21;
    private static final double WEIGHT_STEP = 0.1;
    private static final double THRESHOLD_STEP = 0.025;
    private static final double THRESHOLD_START = 0.20;

    public EvaluationConfig {
        if (categoryWeight < 0.1 - 1e-12 || categoryWeight > 0.7 + 1e-12) {
            throw new IllegalArgumentException(
                    "category weight is outside the evaluation grid: " + categoryWeight);
        }
        if (threshold < THRESHOLD_START - 1e-12 || threshold > 0.7 + 1e-12) {
            throw new IllegalArgumentException(
                    "threshold is outside the evaluation grid: " + threshold);
        }
    }

    public static EvaluationConfig baseline() {
        return new EvaluationConfig(BASELINE_CATEGORY_WEIGHT, BASELINE_THRESHOLD);
    }

    public static List<EvaluationConfig> grid() {
        List<EvaluationConfig> configs = new ArrayList<>(WEIGHT_STEPS * THRESHOLD_STEPS);
        for (int weightIndex = 1; weightIndex <= WEIGHT_STEPS; weightIndex++) {
            for (int thresholdIndex = 0; thresholdIndex < THRESHOLD_STEPS; thresholdIndex++) {
                configs.add(new EvaluationConfig(
                        weightIndex / 10.0,
                        roundThreshold(THRESHOLD_START + thresholdIndex * THRESHOLD_STEP)));
            }
        }
        return List.copyOf(configs);
    }

    private static double roundThreshold(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    public double textWeight() {
        return 1.0 - categoryWeight;
    }

    public SimilaritySettings settings() {
        return new SimilaritySettings(
                categoryWeight,
                textWeight(),
                0.35,
                0.15,
                0.35,
                0.15,
                threshold,
                5,
                20);
    }

    /**
     * Distance from the baseline in grid steps. Used only as the final deterministic tie-breaker.
     */
    public int distanceToBaselineInGridSteps() {
        int weightSteps = (int) Math.round(
                Math.abs(categoryWeight - BASELINE_CATEGORY_WEIGHT) / WEIGHT_STEP);
        int thresholdSteps = (int) Math.round(
                Math.abs(threshold - BASELINE_THRESHOLD) / THRESHOLD_STEP);
        return weightSteps + thresholdSteps;
    }

    public String label() {
        return String.format(
                Locale.ROOT, "w=%.1f/t=%.3f", categoryWeight, threshold);
    }

    public boolean sameAs(EvaluationConfig other) {
        return other != null
                && Math.abs(categoryWeight - other.categoryWeight) < 1e-12
                && Math.abs(threshold - other.threshold) < 1e-12;
    }
}
