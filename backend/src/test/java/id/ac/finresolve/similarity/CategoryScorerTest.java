package id.ac.finresolve.similarity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import id.ac.finresolve.categories.CategoryValues;
import java.util.List;
import org.junit.jupiter.api.Test;

class CategoryScorerTest {

    private static final SimilaritySettings DEFAULTS = SimilaritySettings.defaults();

    private final CategoryScorer scorer = new CategoryScorer();

    @Test
    void matchingRequiredFieldsIgnoreAbsentOptionalQueryFields() {
        CategoryScorer.CategoryScore result = scorer.score(
                new CategoryValues("Checking account", null, "Managing an account", null),
                new CategoryValues(
                        "Checking account", "Checking account",
                        "Managing an account", "Deposits and withdrawals"),
                DEFAULTS);

        assertThat(result.score()).isCloseTo(1.0, within(1e-12));
        assertThat(result.fields())
                .extracting(CategoryScorer.FieldMatch::field)
                .containsExactly("product", "subProduct", "issue", "subIssue");
        assertThat(result.fields())
                .extracting(CategoryScorer.FieldMatch::status)
                .containsExactly("MATCH", "NOT_USED", "MATCH", "NOT_USED");
        assertThat(result.fields().get(1).effectiveWeight()).isZero();
        assertThat(result.fields().get(3).effectiveWeight()).isZero();
        assertThat(contributionSum(result)).isCloseTo(result.score(), within(1e-12));
    }

    @Test
    void missingOptionalCandidateFieldCountsItsWeightWithoutContributing() {
        CategoryScorer.CategoryScore result = scorer.score(
                new CategoryValues(
                        "Checking account", "Checking account",
                        "Managing an account", "Deposits and withdrawals"),
                new CategoryValues("Checking account", null, "Managing an account", "Deposits and withdrawals"),
                DEFAULTS);

        assertThat(result.score()).isCloseTo(0.85, within(1e-12));
        assertThat(result.fields())
                .extracting(CategoryScorer.FieldMatch::status)
                .containsExactly("MATCH", "MISSING_IN_CASE", "MATCH", "MATCH");
        assertThat(result.fields().get(1).configuredWeight()).isEqualTo(0.15);
        assertThat(result.fields().get(1).effectiveWeight()).isEqualTo(0.15);
        assertThat(result.fields().get(1).contribution()).isZero();
        assertThat(contributionSum(result)).isCloseTo(result.score(), within(1e-12));
    }

    @Test
    void matchingIsCaseInsensitiveAfterNormalization() {
        CategoryScorer.CategoryScore result = scorer.score(
                new CategoryValues("CHECKING ACCOUNT", null, "Managing an account", null),
                new CategoryValues("checking account", null, "MANAGING AN ACCOUNT", null),
                DEFAULTS);

        assertThat(result.score()).isCloseTo(1.0, within(1e-12));
        assertThat(result.fields())
                .extracting(CategoryScorer.FieldMatch::status)
                .containsExactly("MATCH", "NOT_USED", "MATCH", "NOT_USED");
    }

    @Test
    void mismatchCountsItsWeightWithoutContributing() {
        CategoryScorer.CategoryScore result = scorer.score(
                new CategoryValues("Checking account", null, "Managing an account", null),
                new CategoryValues("Checking account", null, "Closing an account", null),
                DEFAULTS);

        assertThat(result.score()).isCloseTo(0.5, within(1e-12));
        assertThat(result.fields().get(2).status()).isEqualTo("MISMATCH");
        assertThat(result.fields().get(2).effectiveWeight()).isEqualTo(0.5);
        assertThat(result.fields().get(2).contribution()).isZero();
        assertThat(contributionSum(result)).isCloseTo(result.score(), within(1e-12));
    }

    @Test
    void nullQueryOptionalFieldsAreNotUsedEvenWhenCandidateHasValues() {
        CategoryScorer.CategoryScore result = scorer.score(
                new CategoryValues("Checking account", null, "Managing an account", null),
                new CategoryValues(
                        "Checking account", "Checking account",
                        "Managing an account", "Deposits and withdrawals"),
                DEFAULTS);

        assertThat(result.fields().get(1).status()).isEqualTo("NOT_USED");
        assertThat(result.fields().get(1).queryValue()).isNull();
        assertThat(result.fields().get(1).caseValue()).isEqualTo("Checking account");
        assertThat(result.fields().get(3).status()).isEqualTo("NOT_USED");
        assertThat(result.fields().get(1).effectiveWeight()).isZero();
        assertThat(contributionSum(result)).isCloseTo(result.score(), within(1e-12));
    }

    @Test
    void blankQueryValuesAreTreatedAsAbsent() {
        CategoryScorer.CategoryScore result = scorer.score(
                new CategoryValues("Checking account", "   ", "Managing an account", "\t"),
                new CategoryValues("Checking account", null, "Managing an account", null),
                DEFAULTS);

        assertThat(result.score()).isCloseTo(1.0, within(1e-12));
        assertThat(result.fields())
                .extracting(CategoryScorer.FieldMatch::status)
                .containsExactly("MATCH", "NOT_USED", "MATCH", "NOT_USED");
    }

    @Test
    void everyExplanationRecordsConfiguredAndEffectiveWeights() {
        CategoryScorer.CategoryScore result = scorer.score(
                new CategoryValues("A", "B", "C", "D"),
                new CategoryValues("A", "B", "C", "D"),
                DEFAULTS);

        assertThat(result.fields())
                .extracting(CategoryScorer.FieldMatch::configuredWeight)
                .containsExactly(0.35, 0.15, 0.35, 0.15);
        assertThat(result.fields())
                .extracting(CategoryScorer.FieldMatch::effectiveWeight)
                .containsExactly(0.35, 0.15, 0.35, 0.15);
        assertThat(result.fields())
                .extracting(CategoryScorer.FieldMatch::queryValue)
                .containsExactly("A", "B", "C", "D");
        assertThat(result.fields())
                .extracting(CategoryScorer.FieldMatch::caseValue)
                .containsExactly("A", "B", "C", "D");
        assertThat(contributionSum(result)).isCloseTo(1.0, within(1e-12));
    }

    @Test
    void noUsedFieldsProduceZeroScore() {
        CategoryScorer.CategoryScore result = scorer.score(
                new CategoryValues(null, null, null, null),
                new CategoryValues("A", "B", "C", "D"),
                DEFAULTS);

        assertThat(result.score()).isZero();
        assertThat(result.fields())
                .extracting(CategoryScorer.FieldMatch::status)
                .containsOnly("NOT_USED");
        assertThat(result.fields())
                .allSatisfy(field -> {
                    assertThat(field.effectiveWeight()).isZero();
                    assertThat(field.contribution()).isZero();
                });
    }

    @Test
    void exposesImmutableExplanations() {
        CategoryScorer.CategoryScore result = scorer.score(
                new CategoryValues("A", "B", "C", "D"),
                new CategoryValues("A", "B", "C", "D"),
                DEFAULTS);

        assertThat(result.fields()).isUnmodifiable();
        assertThat(result.fields()).hasSize(4);
    }

    private static double contributionSum(CategoryScorer.CategoryScore result) {
        return result.fields().stream()
                .mapToDouble(CategoryScorer.FieldMatch::contribution)
                .sum();
    }
}
