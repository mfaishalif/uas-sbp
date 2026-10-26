package id.ac.finresolve.similarity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class SimilaritySettingsTest {

    @Test
    void defaultSettingsMatchTheDesign() {
        SimilaritySettings settings = SimilaritySettings.defaults();

        assertThat(settings.categoryWeight()).isEqualTo(0.4);
        assertThat(settings.textWeight()).isEqualTo(0.6);
        assertThat(settings.productWeight()).isEqualTo(0.35);
        assertThat(settings.subProductWeight()).isEqualTo(0.15);
        assertThat(settings.issueWeight()).isEqualTo(0.35);
        assertThat(settings.subIssueWeight()).isEqualTo(0.15);
        assertThat(settings.threshold()).isEqualTo(0.5);
        assertThat(settings.defaultLimit()).isEqualTo(5);
        assertThat(settings.maxLimit()).isEqualTo(20);
        assertThat(settings.algorithmVersion()).isEqualTo("tfidf-cosine-v1");
    }

    @Test
    void acceptsWeightsWhoseSumsAreWithinTheTolerance() {
        SimilaritySettings settings = new SimilaritySettings(
                0.4000000004, 0.5999999996,
                0.3500000004, 0.15, 0.3499999996, 0.15,
                0.5, 5, 20);

        assertThat(settings.categoryWeight() + settings.textWeight())
                .isCloseTo(1.0, org.assertj.core.api.Assertions.within(1e-12));
    }

    @Test
    void allowsZeroCategoryOrTextWeightButNotBothInvalid() {
        assertThat(new SimilaritySettings(
                        0.0, 1.0, 0.35, 0.15, 0.35, 0.15, 0.5, 5, 20)
                .textWeight())
                .isEqualTo(1.0);
        assertThat(new SimilaritySettings(
                        1.0, 0.0, 0.35, 0.15, 0.35, 0.15, 0.5, 5, 20)
                .categoryWeight())
                .isEqualTo(1.0);
    }

    @ParameterizedTest
    @MethodSource("invalidWeights")
    void rejectsNegativeAndNonFiniteWeights(
            double categoryWeight,
            double textWeight,
            double productWeight,
            double subProductWeight,
            double issueWeight,
            double subIssueWeight,
            double threshold) {
        assertThatThrownBy(() -> settings(
                        categoryWeight, textWeight,
                        productWeight, subProductWeight, issueWeight, subIssueWeight,
                        threshold, 5, 20))
                .isInstanceOf(IllegalArgumentException.class);
    }

    static Stream<Arguments> invalidWeights() {
        return Stream.of(
                Arguments.of(-0.01, 1.01, 0.35, 0.15, 0.35, 0.15, 0.5),
                Arguments.of(0.4, -0.01, 0.35, 0.15, 0.35, 0.15, 0.5),
                Arguments.of(0.4, 0.6, -0.01, 0.15, 0.35, 0.15, 0.5),
                Arguments.of(0.4, 0.6, 0.35, -0.01, 0.35, 0.15, 0.5),
                Arguments.of(0.4, 0.6, 0.35, 0.15, -0.01, 0.15, 0.5),
                Arguments.of(0.4, 0.6, 0.35, 0.15, 0.35, -0.01, 0.5),
                Arguments.of(Double.NaN, 0.6, 0.35, 0.15, 0.35, 0.15, 0.5),
                Arguments.of(0.4, Double.POSITIVE_INFINITY, 0.35, 0.15, 0.35, 0.15, 0.5),
                Arguments.of(0.4, 0.6, Double.NaN, 0.15, 0.35, 0.15, 0.5),
                Arguments.of(0.4, 0.6, 0.35, Double.NEGATIVE_INFINITY, 0.35, 0.15, 0.5),
                Arguments.of(0.4, 0.6, 0.35, 0.15, Double.NaN, 0.15, 0.5),
                Arguments.of(0.4, 0.6, 0.35, 0.15, 0.35, Double.POSITIVE_INFINITY, 0.5),
                Arguments.of(0.4, 0.6, 0.35, 0.15, 0.35, 0.15, Double.NaN));
    }

    @Test
    void rejectsCombinedWeightsOutsideTolerance() {
        assertThatThrownBy(() -> settings(
                        0.4, 0.600000002, 0.35, 0.15, 0.35, 0.15, 0.5, 5, 20))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("category and text weights");
    }

    @Test
    void rejectsFieldWeightsOutsideTolerance() {
        assertThatThrownBy(() -> settings(
                        0.4, 0.6, 0.350000002, 0.15, 0.35, 0.15, 0.5, 5, 20))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("field weights");
    }

    @Test
    void rejectsAZeroProductPlusIssueDenominator() {
        assertThatThrownBy(() -> settings(
                        0.4, 0.6, 0.0, 0.5, 0.0, 0.5, 0.5, 5, 20))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("product and issue");
    }

    @Test
    void rejectsThresholdsOutsideTheUnitInterval() {
        assertThatThrownBy(() -> settings(0.4, 0.6, 0.35, 0.15, 0.35, 0.15, -0.000001, 5, 20))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("threshold");
        assertThatThrownBy(() -> settings(0.4, 0.6, 0.35, 0.15, 0.35, 0.15, 1.000001, 5, 20))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("threshold");
    }

    @Test
    void rejectsLimitsOutsideTheContractCeiling() {
        assertThatThrownBy(() -> settings(0.4, 0.6, 0.35, 0.15, 0.35, 0.15, 0.5, 0, 20))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("default limit");
        assertThatThrownBy(() -> settings(0.4, 0.6, 0.35, 0.15, 0.35, 0.15, 0.5, 21, 20))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("default limit");
        assertThatThrownBy(() -> settings(0.4, 0.6, 0.35, 0.15, 0.35, 0.15, 0.5, 5, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max limit");
        assertThatThrownBy(() -> settings(0.4, 0.6, 0.35, 0.15, 0.35, 0.15, 0.5, 5, 21))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max limit");
        assertThatThrownBy(() -> settings(0.4, 0.6, 0.35, 0.15, 0.35, 0.15, 0.5, 6, 5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("default limit");
    }

    private static SimilaritySettings settings(
            double categoryWeight,
            double textWeight,
            double productWeight,
            double subProductWeight,
            double issueWeight,
            double subIssueWeight,
            double threshold,
            int defaultLimit,
            int maxLimit) {
        return new SimilaritySettings(
                categoryWeight,
                textWeight,
                productWeight,
                subProductWeight,
                issueWeight,
                subIssueWeight,
                threshold,
                defaultLimit,
                maxLimit);
    }
}
