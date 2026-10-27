package id.ac.finresolve.evaluation;

import id.ac.finresolve.similarity.SimilarityMode;
import id.ac.finresolve.similarity.SimilaritySettings;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** One point on the fixed two-mode calibration grid. */
public record EvaluationConfig(
        SimilarityMode mode, double categoryWeight, double threshold) {

    public static final double BASELINE_CATEGORY_WEIGHT = 0.4;
    public static final double BASELINE_THRESHOLD = 0.5;
    private static final double DEFAULT_WEIGHT_STEP = 0.1;
    private static final double DEFAULT_THRESHOLD_STEP = 0.025;

    public EvaluationConfig {
        if (mode == null) {
            throw new IllegalArgumentException("similarity mode is required");
        }
        if (!Double.isFinite(categoryWeight) || categoryWeight < 0.0 || categoryWeight > 1.0) {
            throw new IllegalArgumentException(
                    "category weight must be between 0 and 1: " + categoryWeight);
        }
        if (!Double.isFinite(threshold) || threshold < 0.0 || threshold > 1.0) {
            throw new IllegalArgumentException(
                    "threshold must be between 0 and 1: " + threshold);
        }
    }

    /** Convenience for focused tests; defaults to the baseline mode. */
    public EvaluationConfig(double categoryWeight, double threshold) {
        this(SimilarityMode.BASELINE, categoryWeight, threshold);
    }

    public static EvaluationConfig baseline() {
        return new EvaluationConfig(
                SimilarityMode.BASELINE, BASELINE_CATEGORY_WEIGHT, BASELINE_THRESHOLD);
    }

    public static List<EvaluationConfig> grid(EvaluationGrid grid) {
        List<EvaluationConfig> configs = new ArrayList<>();
        for (SimilarityMode mode : grid.modes()) {
            for (int weightIndex = 0; weightIndex < grid.weightSteps(); weightIndex++) {
                double weight = round(
                        grid.categoryWeightMin() + weightIndex * grid.categoryWeightStep(), 10);
                for (int thresholdIndex = 0;
                        thresholdIndex < grid.thresholdSteps();
                        thresholdIndex++) {
                    double threshold = round(
                            grid.thresholdMin() + thresholdIndex * grid.thresholdStep(), 12);
                    configs.add(new EvaluationConfig(mode, weight, threshold));
                }
            }
        }
        return List.copyOf(configs);
    }

    private static double round(double value, int scale) {
        double factor = Math.pow(10, scale);
        return Math.round(value * factor) / factor;
    }

    public double textWeight() {
        return 1.0 - categoryWeight;
    }

    public SimilaritySettings settings(EvaluationGrid grid) {
        return new SimilaritySettings(
                categoryWeight,
                textWeight(),
                grid.productWeight(),
                grid.subProductWeight(),
                grid.issueWeight(),
                grid.subIssueWeight(),
                threshold,
                grid.limit(),
                20,
                mode);
    }

    /**
     * Distance from the baseline in grid steps. Used only as a deterministic tie-breaker.
     */
    public int distanceToBaselineInGridSteps(EvaluationGrid grid) {
        return distanceToBaselineInGridSteps(
                new EvaluationConfig(
                        SimilarityMode.BASELINE,
                        BASELINE_CATEGORY_WEIGHT,
                        BASELINE_THRESHOLD),
                grid);
    }

    /** Distance from an explicit baseline using default grid steps. */
    public int distanceToBaselineInGridSteps(EvaluationConfig baseline) {
        return distance(baseline, DEFAULT_WEIGHT_STEP, DEFAULT_THRESHOLD_STEP);
    }

    /** Distance from an explicit baseline using the manifest grid steps. */
    public int distanceToBaselineInGridSteps(
            EvaluationConfig baseline, EvaluationGrid grid) {
        return distance(baseline, grid.categoryWeightStep(), grid.thresholdStep());
    }

    private int distance(
            EvaluationConfig baseline, double weightStep, double thresholdStep) {
        int weightSteps = (int) Math.round(
                Math.abs(categoryWeight - baseline.categoryWeight()) / weightStep);
        int thresholdSteps = (int) Math.round(
                Math.abs(threshold - baseline.threshold()) / thresholdStep);
        return weightSteps + thresholdSteps;
    }

    /** Default grid distances for focused tests. */
    public int distanceToBaselineInGridSteps() {
        return distanceToBaselineInGridSteps(
                new EvaluationConfig(
                        SimilarityMode.BASELINE,
                        BASELINE_CATEGORY_WEIGHT,
                        BASELINE_THRESHOLD));
    }

    public String label() {
        return String.format(
                Locale.ROOT, "%s/w=%.1f/t=%.3f", mode.configValue(), categoryWeight, threshold);
    }

    public boolean sameAs(EvaluationConfig other) {
        return other != null
                && mode == other.mode
                && Math.abs(categoryWeight - other.categoryWeight) < 1e-12
                && Math.abs(threshold - other.threshold) < 1e-12;
    }

    /** Fixed grid read from the study manifest. */
    public record EvaluationGrid(
            List<SimilarityMode> modes,
            double categoryWeightMin,
            double categoryWeightMax,
            double categoryWeightStep,
            double thresholdMin,
            double thresholdMax,
            double thresholdStep,
            double productWeight,
            double subProductWeight,
            double issueWeight,
            double subIssueWeight,
            int limit) {

        public EvaluationGrid {
            modes = List.copyOf(modes);
            if (modes.isEmpty()) {
                throw new IllegalArgumentException("At least one similarity mode is required");
            }
            if (categoryWeightStep <= 0.0 || thresholdStep <= 0.0) {
                throw new IllegalArgumentException("Grid steps must be positive");
            }
            if (categoryWeightMin > categoryWeightMax || thresholdMin > thresholdMax) {
                throw new IllegalArgumentException("Grid bounds are inverted");
            }
            if (limit < 1 || limit > 5) {
                throw new IllegalArgumentException(
                        "Evaluation limit must be between 1 and 5: " + limit);
            }
        }

        public int weightSteps() {
            return (int) Math.round(
                    (categoryWeightMax - categoryWeightMin) / categoryWeightStep) + 1;
        }

        public int thresholdSteps() {
            return (int) Math.round(
                    (thresholdMax - thresholdMin) / thresholdStep) + 1;
        }
    }
}
