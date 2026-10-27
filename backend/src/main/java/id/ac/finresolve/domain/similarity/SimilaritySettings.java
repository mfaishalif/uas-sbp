package id.ac.finresolve.domain.similarity;

/**
 * Immutable, startup-validated scoring configuration.
 *
 * <p>Weights are fractions, not percentages. All optional query category fields are excluded from
 * the category denominator, so the product and issue weights must leave at least one used field.
 * The mode selects the tokenizer pipeline and therefore the reported algorithm version.
 */
public record SimilaritySettings(
        double categoryWeight,
        double textWeight,
        double productWeight,
        double subProductWeight,
        double issueWeight,
        double subIssueWeight,
        double threshold,
        int defaultLimit,
        int maxLimit,
        SimilarityMode mode) {

    private static final double WEIGHT_SUM_TOLERANCE = 1e-9;
    private static final int CONTRACT_MIN_LIMIT = 1;
    private static final int CONTRACT_MAX_LIMIT = 20;

    public SimilaritySettings {
        checkWeight("category weight", categoryWeight);
        checkWeight("text weight", textWeight);
        checkWeight("product weight", productWeight);
        checkWeight("sub product weight", subProductWeight);
        checkWeight("issue weight", issueWeight);
        checkWeight("sub issue weight", subIssueWeight);

        if (Math.abs(categoryWeight + textWeight - 1.0) > WEIGHT_SUM_TOLERANCE) {
            throw new IllegalArgumentException(
                    "category and text weights must sum to 1");
        }
        if (Math.abs(productWeight + subProductWeight + issueWeight + subIssueWeight - 1.0)
                > WEIGHT_SUM_TOLERANCE) {
            throw new IllegalArgumentException("field weights must sum to 1");
        }
        if (productWeight + issueWeight <= 0.0) {
            throw new IllegalArgumentException(
                    "product and issue weights must leave a nonzero category denominator");
        }
        if (!Double.isFinite(threshold) || threshold < 0.0 || threshold > 1.0) {
            throw new IllegalArgumentException("threshold must be between 0 and 1");
        }
        if (defaultLimit < CONTRACT_MIN_LIMIT || defaultLimit > CONTRACT_MAX_LIMIT) {
            throw new IllegalArgumentException(
                    "default limit must be between " + CONTRACT_MIN_LIMIT
                            + " and " + CONTRACT_MAX_LIMIT);
        }
        if (maxLimit < CONTRACT_MIN_LIMIT || maxLimit > CONTRACT_MAX_LIMIT) {
            throw new IllegalArgumentException(
                    "max limit must be between " + CONTRACT_MIN_LIMIT
                            + " and " + CONTRACT_MAX_LIMIT);
        }
        if (defaultLimit > maxLimit) {
            throw new IllegalArgumentException("default limit must not exceed max limit");
        }
        if (mode == null) {
            throw new IllegalArgumentException("similarity mode is required");
        }
    }

    /**
     * Backward-compatible baseline constructor used by focused scoring tests.
     */
    public SimilaritySettings(
            double categoryWeight,
            double textWeight,
            double productWeight,
            double subProductWeight,
            double issueWeight,
            double subIssueWeight,
            double threshold,
            int defaultLimit,
            int maxLimit) {
        this(
                categoryWeight,
                textWeight,
                productWeight,
                subProductWeight,
                issueWeight,
                subIssueWeight,
                threshold,
                defaultLimit,
                maxLimit,
                SimilarityMode.BASELINE);
    }

    public static SimilaritySettings defaults() {
        return new SimilaritySettings(
                0.4, 0.6,
                0.35, 0.15, 0.35, 0.15,
                0.5, 5, 20,
                SimilarityMode.BASELINE);
    }

    public String algorithmVersion() {
        return mode.algorithmVersion();
    }

    private static void checkWeight(String name, double value) {
        if (!Double.isFinite(value) || value < 0.0) {
            throw new IllegalArgumentException(name + " must be finite and non-negative");
        }
    }
}
