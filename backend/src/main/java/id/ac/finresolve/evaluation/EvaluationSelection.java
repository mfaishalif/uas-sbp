package id.ac.finresolve.evaluation;

import id.ac.finresolve.domain.similarity.SimilarityMode;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Calibration-only configuration selection and the final-test acceptance gate. */
public final class EvaluationSelection {

    public static final double EPSILON = 1e-12;

    private EvaluationSelection() {
    }

    /**
     * Selects only from calibration aggregates. Eligible candidates must not regress macro
     * precision, micro precision, per-query success, or negative failures against the current
     * baseline. The best eligible point maximizes precision@5, then micro precision, per-query
     * success, fewer negative failures, baseline mode, and finally distance from the baseline
     * grid point. The calibration test is unused when precision@5 does not improve.
     */
    public static Selection select(
            List<EvaluationConfig> configs,
            EvaluationConfig baseline,
            Map<EvaluationConfig, EvaluationMetrics.Aggregate> calibration) {
        return select(configs, baseline, calibration, null);
    }

    public static Selection select(
            List<EvaluationConfig> configs,
            EvaluationConfig baseline,
            Map<EvaluationConfig, EvaluationMetrics.Aggregate> calibration,
            EvaluationConfig.EvaluationGrid grid) {
        EvaluationMetrics.Aggregate baselineAggregate = calibration.get(baseline);
        if (baselineAggregate == null) {
            throw new IllegalArgumentException("Baseline is missing from calibration metrics");
        }

        List<EvaluationConfig> eligible = configs.stream()
                .filter(config -> isEligible(calibration.get(config), baselineAggregate))
                .toList();
        EvaluationConfig bestEligible = eligible.stream()
                .min(comparator(calibration, baseline, grid))
                .orElseThrow(() -> new IllegalArgumentException(
                        "No eligible calibration configuration is available"));

        boolean improved = bestEligible != null
                && calibration.get(bestEligible).meanPrecisionAt5()
                        > baselineAggregate.meanPrecisionAt5() + EPSILON;
        return new Selection(
                improved ? bestEligible : baseline,
                bestEligible,
                improved,
                eligible.size());
    }

    /** Best raw point in one mode, used only for reporting the decomposition of gains. */
    public static EvaluationConfig bestInMode(
            List<EvaluationConfig> configs,
            SimilarityMode mode,
            EvaluationConfig baseline,
            Map<EvaluationConfig, EvaluationMetrics.Aggregate> calibration,
            EvaluationConfig.EvaluationGrid grid) {
        return configs.stream()
                .filter(config -> config.mode() == mode)
                .min(comparator(calibration, baseline, grid))
                .orElse(null);
    }

    public static boolean isEligible(
            EvaluationMetrics.Aggregate candidate,
            EvaluationMetrics.Aggregate baseline) {
        if (candidate == null) {
            return false;
        }
        return candidate.macroPrecision() + EPSILON >= baseline.macroPrecision()
                && candidate.microPrecision() + EPSILON >= baseline.microPrecision()
                && candidate.complaintSuccessRate() + EPSILON >= baseline.complaintSuccessRate()
                && candidate.negativeFailures() <= baseline.negativeFailures();
    }

    private static Comparator<EvaluationConfig> comparator(
            Map<EvaluationConfig, EvaluationMetrics.Aggregate> calibration,
            EvaluationConfig baseline,
            EvaluationConfig.EvaluationGrid grid) {
        return (left, right) -> {
            EvaluationMetrics.Aggregate leftAggregate = calibration.get(left);
            EvaluationMetrics.Aggregate rightAggregate = calibration.get(right);

            int comparison = compareDescending(
                    leftAggregate.meanPrecisionAt5(),
                    rightAggregate.meanPrecisionAt5());
            if (comparison != 0) {
                return comparison;
            }
            comparison = compareDescending(
                    leftAggregate.microPrecision(), rightAggregate.microPrecision());
            if (comparison != 0) {
                return comparison;
            }
            comparison = compareDescending(
                    leftAggregate.complaintSuccessRate(),
                    rightAggregate.complaintSuccessRate());
            if (comparison != 0) {
                return comparison;
            }
            comparison = Integer.compare(
                    leftAggregate.negativeFailures(),
                    rightAggregate.negativeFailures());
            if (comparison != 0) {
                return comparison;
            }
            comparison = Integer.compare(
                    left.mode() == SimilarityMode.BASELINE ? 0 : 1,
                    right.mode() == SimilarityMode.BASELINE ? 0 : 1);
            if (comparison != 0) {
                return comparison;
            }
            comparison = Integer.compare(
                    grid == null
                            ? left.distanceToBaselineInGridSteps(baseline)
                            : left.distanceToBaselineInGridSteps(baseline, grid),
                    grid == null
                            ? right.distanceToBaselineInGridSteps(baseline)
                            : right.distanceToBaselineInGridSteps(baseline, grid));
            if (comparison != 0) {
                return comparison;
            }
            comparison = Double.compare(left.categoryWeight(), right.categoryWeight());
            if (comparison != 0) {
                return comparison;
            }
            return Double.compare(left.threshold(), right.threshold());
        };
    }

    private static int compareDescending(double left, double right) {
        if (Math.abs(left - right) <= EPSILON) {
            return 0;
        }
        return Double.compare(right, left);
    }

    public static Gate gate(
            EvaluationMetrics.Aggregate baseline,
            EvaluationMetrics.Aggregate selected) {
        boolean precisionAt5Increased =
                selected.meanPrecisionAt5() > baseline.meanPrecisionAt5() + EPSILON;
        boolean macroPrecisionNotLower =
                selected.macroPrecision() + EPSILON >= baseline.macroPrecision();
        boolean microPrecisionNotLower =
                selected.microPrecision() + EPSILON >= baseline.microPrecision();
        boolean complaintSuccessNotLower =
                selected.complaintSuccessRate() + EPSILON >= baseline.complaintSuccessRate();
        boolean negativeFailuresNotIncreased =
                selected.negativeFailures() <= baseline.negativeFailures();
        return new Gate(
                precisionAt5Increased,
                macroPrecisionNotLower,
                microPrecisionNotLower,
                complaintSuccessNotLower,
                negativeFailuresNotIncreased,
                precisionAt5Increased
                        && macroPrecisionNotLower
                        && microPrecisionNotLower
                        && complaintSuccessNotLower
                        && negativeFailuresNotIncreased);
    }

    public record Selection(
            EvaluationConfig selected,
            EvaluationConfig bestEligible,
            boolean precisionImproved,
            int eligibleCandidates) {
    }

    public record Gate(
            boolean precisionAt5Increased,
            boolean macroPrecisionNotLower,
            boolean microPrecisionNotLower,
            boolean complaintSuccessNotLower,
            boolean negativeFailuresNotIncreased,
            boolean passed) {
    }
}
