package id.ac.finresolve.similarity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import id.ac.finresolve.cases.CaseRecord;
import id.ac.finresolve.cases.ComplaintInput;
import id.ac.finresolve.categories.CategoryValues;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SimilarityEngineTest {

    private static final SimilaritySettings DEFAULTS = SimilaritySettings.defaults();

    private final SimilarityEngine engine = new SimilarityEngine();

    @Test
    void includesACandidateExactlyAtTheThreshold() {
        SimilaritySettings categoryOnly = categoryOnlySettings(0.5, 0.5);
        String narrative = "shared narrative text";
        CaseRecord candidate = caseRecord(
                uuid(1), CaseRecord.Source.CFPB,
                new CategoryValues("P", null, "I-other", null), narrative);

        List<SimilarityResult> results = engine.rank(
                complaint("P", null, "I-query", null, narrative),
                List.of(candidate), 5, categoryOnly);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).categoryScore()).isCloseTo(0.5, within(1e-12));
        assertThat(results.get(0).score()).isCloseTo(0.5, within(1e-12));
        assertThat(results.get(0).textScore()).isCloseTo(1.0, within(1e-12));
    }

    @Test
    void excludesACandidateJustBelowTheThresholdWithoutRounding() {
        SimilaritySettings categoryOnly = categoryOnlySettings(0.49999999995, 0.50000000005);
        String narrative = "shared narrative text";
        CaseRecord candidate = caseRecord(
                uuid(1), CaseRecord.Source.CFPB,
                new CategoryValues("P", null, "I-other", null), narrative);

        List<SimilarityResult> results = engine.rank(
                complaint("P", null, "I-query", null, narrative),
                List.of(candidate), 5, categoryOnly);

        assertThat(results).isEmpty();
    }

    @Test
    void excludesACategoryOnlyMatchWithoutTextualEvidence() {
        SimilaritySettings categoryOnly = categoryOnlySettings(0.5, 0.5);
        CaseRecord candidate = caseRecord(
                uuid(1), CaseRecord.Source.CFPB,
                new CategoryValues("P", null, "I-other", null), "alpha beta");

        List<SimilarityResult> results = engine.rank(
                complaint("P", null, "I-query", null, "gamma delta"),
                List.of(candidate), 5, categoryOnly);

        assertThat(results).isEmpty();
    }

    @Test
    void ranksCandidatesAcrossProductsAndSourcesWithoutPrefiltering() {
        String narrative = "transfer fee refund";
        CategoryValues checking = new CategoryValues(
                "Checking or savings account", null, "Managing an account", null);
        CategoryValues creditCard = new CategoryValues(
                "Credit card", null, "Billing dispute", null);
        UUID cfpbId = uuid(1);
        UUID analystId = uuid(2);
        UUID noTextId = uuid(3);

        List<SimilarityResult> results = engine.rank(
                complaint(
                        "Checking or savings account", null, "Managing an account", null,
                        narrative),
                List.of(
                        caseRecord(cfpbId, CaseRecord.Source.CFPB, checking, narrative),
                        caseRecord(analystId, CaseRecord.Source.ANALYST, creditCard, narrative),
                        caseRecord(
                                noTextId, CaseRecord.Source.CFPB, checking,
                                "mortgage property appraisal value")),
                5,
                DEFAULTS);

        assertThat(results).extracting(result -> result.caseRecord().id())
                .containsExactly(cfpbId, analystId);
        assertThat(results).extracting(result -> result.caseRecord().source())
                .containsExactly(CaseRecord.Source.CFPB, CaseRecord.Source.ANALYST);
        assertThat(results.get(1).categoryScore()).isZero();
        assertThat(results.get(1).score()).isCloseTo(0.6, within(1e-12));
    }

    @Test
    void equalTotalsSortByUuidStringAscending() {
        CategoryValues categories = new CategoryValues(
                "Checking or savings account", null, "Managing an account", null);
        String narrative = "transfer fee refund";
        UUID third = uuid(3);
        UUID first = uuid(1);
        UUID second = uuid(2);

        List<SimilarityResult> results = engine.rank(
                complaint(
                        "Checking or savings account", null, "Managing an account", null,
                        narrative),
                List.of(
                        caseRecord(third, CaseRecord.Source.CFPB, categories, narrative),
                        caseRecord(first, CaseRecord.Source.CFPB, categories, narrative),
                        caseRecord(second, CaseRecord.Source.ANALYST, categories, narrative)),
                5,
                DEFAULTS);

        assertThat(results).extracting(result -> result.caseRecord().id())
                .containsExactly(first, second, third);
    }

    @Test
    void appliesTheLimitAfterSorting() {
        CategoryValues categories = new CategoryValues(
                "Checking or savings account", null, "Managing an account", null);
        String narrative = "transfer fee refund";

        List<SimilarityResult> results = engine.rank(
                complaint(
                        "Checking or savings account", null, "Managing an account", null,
                        narrative),
                List.of(
                        caseRecord(uuid(3), CaseRecord.Source.CFPB, categories, narrative),
                        caseRecord(uuid(1), CaseRecord.Source.CFPB, categories, narrative),
                        caseRecord(uuid(2), CaseRecord.Source.CFPB, categories, narrative)),
                2,
                DEFAULTS);

        assertThat(results).extracting(result -> result.caseRecord().id())
                .containsExactly(uuid(1), uuid(2));
    }

    @Test
    void clampsTheLimitToTheConfiguredCeiling() {
        SimilaritySettings twoAtMost = new SimilaritySettings(
                0.4, 0.6, 0.35, 0.15, 0.35, 0.15, 0.5, 2, 2);
        CategoryValues categories = new CategoryValues(
                "Checking or savings account", null, "Managing an account", null);
        String narrative = "transfer fee refund";

        List<SimilarityResult> results = engine.rank(
                complaint(
                        "Checking or savings account", null, "Managing an account", null,
                        narrative),
                List.of(
                        caseRecord(uuid(1), CaseRecord.Source.CFPB, categories, narrative),
                        caseRecord(uuid(2), CaseRecord.Source.CFPB, categories, narrative),
                        caseRecord(uuid(3), CaseRecord.Source.CFPB, categories, narrative)),
                20,
                twoAtMost);

        assertThat(results).hasSize(2);
    }

    @Test
    void rejectsLimitsBelowOne() {
        assertThatThrownBy(() -> engine.rank(
                        complaint("P", null, "I", null, "transfer fee"),
                        List.of(),
                        0,
                        DEFAULTS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("limit");
    }

    @Test
    void combinesCategoryAndTextScoresWithoutRounding() {
        SimilaritySettings weights = new SimilaritySettings(
                0.25, 0.75, 0.35, 0.15, 0.35, 0.15, 0.0, 5, 20);
        String queryNarrative = "alpha beta gamma delta epsilon";
        CaseRecord candidate = caseRecord(
                uuid(1), CaseRecord.Source.CFPB,
                new CategoryValues("P", "SP", "different", "SI"),
                "alpha beta gamma delta zeta");
        List<CaseRecord> corpus = List.of(candidate);
        TfidfSnapshot snapshot = TfidfSnapshot.fit(corpus, new EnglishTokenizer());
        double expectedText = snapshot.compare(queryNarrative, candidate.id()).score();
        double expectedCategory = new CategoryScorer().score(
                        new CategoryValues("P", "SP", "I", "SI"),
                        candidate.categories(),
                        weights)
                .score();

        List<SimilarityResult> results = engine.rank(
                complaint("P", "SP", "I", "SI", queryNarrative),
                corpus, 5, weights);

        assertThat(results).hasSize(1);
        SimilarityResult result = results.get(0);
        assertThat(result.categoryScore()).isCloseTo(expectedCategory, within(1e-12));
        assertThat(result.textScore()).isCloseTo(expectedText, within(1e-12));
        assertThat(result.score())
                .isCloseTo(0.25 * expectedCategory + 0.75 * expectedText, within(1e-12));
        assertThat(result.score()).isNotEqualTo(
                Math.round(result.score() * 100.0) / 100.0);
    }

    @Test
    void resultsCarryFourFieldExplanationsAndBoundedSharedTerms() {
        String narrative = "alpha bravo charlie delta echo foxtrot golf hotel india juliet kilo";
        CaseRecord candidate = caseRecord(
                uuid(1), CaseRecord.Source.CFPB,
                new CategoryValues("P", "SP", "I", "SI"), narrative);

        List<SimilarityResult> results = engine.rank(
                complaint("P", null, "I", null, narrative),
                List.of(candidate), 5, DEFAULTS);

        assertThat(results).hasSize(1);
        SimilarityResult result = results.get(0);
        assertThat(result.categoryExplanation())
                .extracting(CategoryScorer.FieldMatch::field)
                .containsExactly("product", "subProduct", "issue", "subIssue");
        assertThat(result.categoryExplanation())
                .extracting(CategoryScorer.FieldMatch::status)
                .containsExactly("MATCH", "NOT_USED", "MATCH", "NOT_USED");
        assertThat(result.categoryExplanation().stream()
                .mapToDouble(CategoryScorer.FieldMatch::contribution)
                .sum())
                .isCloseTo(result.categoryScore(), within(1e-12));
        assertThat(result.sharedTerms()).hasSize(10);
        assertThat(result.categoryExplanation()).isUnmodifiable();
        assertThat(result.sharedTerms()).isUnmodifiable();
    }

    @Test
    void emptyCorpusRanksNothing() {
        assertThat(engine.rank(
                        complaint("P", null, "I", null, "transfer fee"),
                        List.of(),
                        5,
                        DEFAULTS))
                .isEmpty();
    }

    @Test
    void noEligibleCandidateProducesNoResults() {
        CaseRecord candidate = caseRecord(
                uuid(1), CaseRecord.Source.CFPB,
                new CategoryValues("Other", null, "Other issue", null),
                "mortgage property appraisal value");

        assertThat(engine.rank(
                        complaint("P", null, "I", null, "transfer fee"),
                        List.of(candidate),
                        5,
                        DEFAULTS))
                .isEmpty();
    }

    @Test
    void phrasesModeMatchesParaphrasesAndExposesCanonicalSharedTerms() {
        SimilaritySettings phrases = new SimilaritySettings(
                0.4, 0.6, 0.35, 0.15, 0.35, 0.15, 0.5, 5, 20,
                SimilarityMode.PHRASES);
        CaseRecord candidate = caseRecord(
                uuid(1), CaseRecord.Source.CFPB,
                new CategoryValues("P", null, "I", null),
                "The ATM charged twice and the funds held were never released.");

        List<SimilarityResult> results = engine.rank(
                complaint(
                        "P", null, "I", null,
                        "A cash machine charged double and the withheld balance stayed unavailable."),
                List.of(candidate), 5, phrases);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).textScore()).isCloseTo(Math.sqrt(3.0 / 5.0), within(1e-12));
        assertThat(results.get(0).sharedTerms())
                .extracting(TfidfSnapshot.Term::term)
                .containsExactlyInAnyOrder("atm", "duplicatecharge", "heldfunds");
        assertThat(results.get(0).sharedTerms().stream()
                .mapToDouble(TfidfSnapshot.Term::contribution)
                .sum())
                .isCloseTo(results.get(0).textScore(), within(1e-12));
    }

    @Test
    void phrasesModeDoesNotMergeAtmDepositAndWithdrawalMechanisms() {
        EnglishTokenizer tokenizer = new EnglishTokenizer(SimilarityMode.PHRASES);

        assertThat(tokenizer.tokens(
                        "The ATM accepted my cash deposit but did not credit the funds."))
                .containsExactly("atm", "accepted", "cash", "deposit", "did", "credit", "funds");
        assertThat(tokenizer.tokens(
                        "The ATM debited my withdrawal but did not dispense cash."))
                .containsExactly("atm", "debited", "withdrawal", "did", "dispense", "cash");
    }

    @Test
    void baselineModeDoesNotReplaceDomainPhrases() {
        CaseRecord candidate = caseRecord(
                uuid(1), CaseRecord.Source.CFPB,
                new CategoryValues("P", null, "I", null),
                "The ATM charged twice and funds held.");

        List<SimilarityResult> results = engine.rank(
                complaint(
                        "P", null, "I", null,
                        "A cash machine duplicate charge withheld balance."),
                List.of(candidate), 5, DEFAULTS);

        assertThat(results).isEmpty();
    }

    private static SimilaritySettings categoryOnlySettings(
            double productWeight, double issueWeight) {
        return new SimilaritySettings(
                1.0, 0.0,
                productWeight, 0.0, issueWeight, 0.0,
                0.5, 5, 20);
    }

    private static ComplaintInput complaint(
            String product, String subProduct, String issue, String subIssue, String narrative) {
        return new ComplaintInput(
                new CategoryValues(product, subProduct, issue, subIssue),
                narrative,
                null,
                null,
                null);
    }

    private static CaseRecord caseRecord(
            UUID id, CaseRecord.Source source, CategoryValues categories, String narrative) {
        return new CaseRecord(
                id,
                source,
                categories,
                narrative,
                null,
                null,
                null,
                Instant.EPOCH,
                Instant.EPOCH,
                null,
                null,
                List.of());
    }

    private static UUID uuid(int value) {
        return UUID.fromString("00000000-0000-0000-0000-%012d".formatted(value));
    }
}
