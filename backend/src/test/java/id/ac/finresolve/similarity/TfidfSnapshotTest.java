package id.ac.finresolve.similarity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import id.ac.finresolve.cases.CaseRecord;
import id.ac.finresolve.categories.CategoryValues;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TfidfSnapshotTest {

    private static final UUID CASE_ONE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID CASE_TWO = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private final EnglishTokenizer tokenizer = new EnglishTokenizer();

    @Test
    void smoothedIdfUsesOnlyCaseDocumentsAndTheCommittedFormula() {
        TfidfSnapshot snapshot = snapshot(
                caseRecord(CASE_ONE, "transfer fee"),
                caseRecord(CASE_TWO, "transfer refund"));

        assertThat(snapshot.idf("transfer")).isCloseTo(1.0, within(1e-12));
        assertThat(snapshot.idf("fee")).isCloseTo(Math.log(3.0 / 2.0) + 1.0, within(1e-12));
        assertThat(snapshot.idf("refund")).isCloseTo(Math.log(3.0 / 2.0) + 1.0, within(1e-12));
    }

    @Test
    void repeatedTermFrequencyChangesTheCosineAsSpecified() {
        TfidfSnapshot snapshot = snapshot(caseRecord(CASE_ONE, "transfer transfer fee"));

        TfidfSnapshot.TextScore score = snapshot.compare("transfer fee", CASE_ONE);

        assertThat(score.score()).isCloseTo(3.0 / Math.sqrt(10.0), within(1e-12));
        assertThat(score.sharedTerms()).hasSize(2);
    }

    @Test
    void repeatedQueryTermFrequencyIsCountedToo() {
        TfidfSnapshot snapshot = snapshot(caseRecord(CASE_ONE, "transfer fee"));

        assertThat(snapshot.compare("transfer transfer fee", CASE_ONE).score())
                .isCloseTo(3.0 / Math.sqrt(10.0), within(1e-12));
    }

    @Test
    void identicalNonzeroDocumentsProduceUnitCosine() {
        TfidfSnapshot snapshot = snapshot(caseRecord(CASE_ONE, "transfer fee"));

        assertThat(snapshot.compare("transfer fee", CASE_ONE).score())
                .isCloseTo(1.0, within(1e-12));
    }

    @Test
    void queryTokensOutsideTheFittedVocabularyAreIgnored() {
        TfidfSnapshot snapshot = snapshot(caseRecord(CASE_ONE, "transfer fee"));

        TfidfSnapshot.TextScore result = snapshot.compare(
                "refund refund transfer", CASE_ONE);

        assertThat(snapshot.idf("fee")).isCloseTo(1.0, within(1e-12));
        assertThat(result.score()).isCloseTo(1.0 / Math.sqrt(2.0), within(1e-12));
        assertThat(result.sharedTerms())
                .extracting(TfidfSnapshot.Term::term)
                .containsExactly("transfer");
    }

    @Test
    void outOfVocabularyQueryHasZeroCosine() {
        TfidfSnapshot snapshot = snapshot(caseRecord(CASE_ONE, "transfer fee"));

        TfidfSnapshot.TextScore result = snapshot.compare("refund refund refund", CASE_ONE);

        assertThat(result.score()).isZero();
        assertThat(result.sharedTerms()).isEmpty();
    }

    @Test
    void zeroNormQueryHasZeroCosine() {
        TfidfSnapshot snapshot = snapshot(caseRecord(CASE_ONE, "transfer fee"));

        assertThat(snapshot.compare("", CASE_ONE).score()).isZero();
        assertThat(snapshot.compare(null, CASE_ONE).score()).isZero();
        assertThat(snapshot.compare("the 123 XX", CASE_ONE).score()).isZero();
    }

    @Test
    void sharedTermsAreLimitedToTenAndTiesUseLexicalOrder() {
        List<String> terms = List.of(
                "alpha", "bravo", "charlie", "delta", "echo", "foxtrot",
                "golf", "hotel", "india", "juliet", "kilo");
        String narrative = String.join(" ", terms);
        TfidfSnapshot snapshot = snapshot(caseRecord(CASE_ONE, narrative));

        TfidfSnapshot.TextScore result = snapshot.compare(narrative, CASE_ONE);

        assertThat(result.score()).isCloseTo(1.0, within(1e-12));
        assertThat(result.sharedTerms())
                .extracting(TfidfSnapshot.Term::term)
                .containsExactly(
                        "alpha", "bravo", "charlie", "delta", "echo",
                        "foxtrot", "golf", "hotel", "india", "juliet");
        assertThat(result.sharedTerms())
                .allSatisfy(term -> assertThat(term.contribution())
                        .isCloseTo(1.0 / 11.0, within(1e-12)));
    }

    @Test
    void sharedTermsSortByContributionDescendingThenTokenAscending() {
        TfidfSnapshot snapshot = snapshot(
                caseRecord(CASE_ONE, "rare common"),
                caseRecord(CASE_TWO, "common"));

        TfidfSnapshot.TextScore result = snapshot.compare("rare common", CASE_ONE);

        assertThat(result.sharedTerms())
                .extracting(TfidfSnapshot.Term::term)
                .containsExactly("rare", "common");
        assertThat(result.sharedTerms().get(0).contribution())
                .isGreaterThan(result.sharedTerms().get(1).contribution());
        assertThat(result.sharedTerms().stream()
                .mapToDouble(TfidfSnapshot.Term::contribution)
                .sum())
                .isCloseTo(result.score(), within(1e-12));
    }

    @Test
    void unknownCaseIdentifiersAreRejected() {
        TfidfSnapshot snapshot = snapshot(caseRecord(CASE_ONE, "transfer fee"));

        assertThatThrownBy(() -> snapshot.compare("transfer", CASE_TWO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(CASE_TWO.toString());
    }

    @Test
    void emptyCorpusCanBeFittedAndReportsItsSize() {
        TfidfSnapshot snapshot = TfidfSnapshot.fit(List.of(), tokenizer);

        assertThat(snapshot.corpusSize()).isZero();
    }

    @Test
    void returnsImmutableScoreDetails() {
        TfidfSnapshot snapshot = snapshot(caseRecord(CASE_ONE, "transfer fee"));

        TfidfSnapshot.TextScore result = snapshot.compare("transfer fee", CASE_ONE);

        assertThat(result.sharedTerms()).isUnmodifiable();
    }

    private TfidfSnapshot snapshot(CaseRecord... cases) {
        return TfidfSnapshot.fit(List.of(cases), tokenizer);
    }

    private static CaseRecord caseRecord(UUID id, String narrative) {
        return new CaseRecord(
                id,
                CaseRecord.Source.CFPB,
                new CategoryValues("P", null, "I", null),
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
}
