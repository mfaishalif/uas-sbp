package id.ac.finresolve.config;

import id.ac.finresolve.domain.similarity.SimilarityMode;
import id.ac.finresolve.domain.similarity.SimilaritySettings;

/**
 * Spring binding for the framework-free {@link SimilaritySettings} domain model.
 *
 * <p>Defaults mirror the committed {@code application.yml}; every value is still validated by the
 * domain constructor before any bean depending on it is created.
 */
public final class SimilarityProperties {

    private double categoryWeight = 0.4;
    private double textWeight = 0.6;
    private double productWeight = 0.35;
    private double subProductWeight = 0.15;
    private double issueWeight = 0.35;
    private double subIssueWeight = 0.15;
    private double threshold = 0.5;
    private int defaultLimit = 5;
    private int maxLimit = 20;
    private String mode = "baseline";

    public double getCategoryWeight() {
        return categoryWeight;
    }

    public void setCategoryWeight(double categoryWeight) {
        this.categoryWeight = categoryWeight;
    }

    public double getTextWeight() {
        return textWeight;
    }

    public void setTextWeight(double textWeight) {
        this.textWeight = textWeight;
    }

    public double getProductWeight() {
        return productWeight;
    }

    public void setProductWeight(double productWeight) {
        this.productWeight = productWeight;
    }

    public double getSubProductWeight() {
        return subProductWeight;
    }

    public void setSubProductWeight(double subProductWeight) {
        this.subProductWeight = subProductWeight;
    }

    public double getIssueWeight() {
        return issueWeight;
    }

    public void setIssueWeight(double issueWeight) {
        this.issueWeight = issueWeight;
    }

    public double getSubIssueWeight() {
        return subIssueWeight;
    }

    public void setSubIssueWeight(double subIssueWeight) {
        this.subIssueWeight = subIssueWeight;
    }

    public double getThreshold() {
        return threshold;
    }

    public void setThreshold(double threshold) {
        this.threshold = threshold;
    }

    public int getDefaultLimit() {
        return defaultLimit;
    }

    public void setDefaultLimit(int defaultLimit) {
        this.defaultLimit = defaultLimit;
    }

    public int getMaxLimit() {
        return maxLimit;
    }

    public void setMaxLimit(int maxLimit) {
        this.maxLimit = maxLimit;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public SimilaritySettings toSettings() {
        return new SimilaritySettings(
                categoryWeight,
                textWeight,
                productWeight,
                subProductWeight,
                issueWeight,
                subIssueWeight,
                threshold,
                defaultLimit,
                maxLimit,
                SimilarityMode.parse(mode));
    }
}
