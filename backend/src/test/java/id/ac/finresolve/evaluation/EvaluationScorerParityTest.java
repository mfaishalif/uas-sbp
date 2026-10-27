package id.ac.finresolve.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import id.ac.finresolve.cases.CaseRecord;
import id.ac.finresolve.categories.CategoryValues;
import id.ac.finresolve.similarity.SimilarityEngine;
import id.ac.finresolve.similarity.SimilarityResult;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EvaluationScorerParityTest {

    @Test
    void evaluationRankingMatchesTheProductionScorer() {
        List<CaseRecord> cases = List.of(
                caseRecord("101", "Checking account", "Deposits and withdrawals",
                        "A transfer was deducted from my checking account but never arrived."),
                caseRecord("102", "Checking account", "Deposits and withdrawals",
                        "My account was charged twice for one payment and the bank did not help."),
                caseRecord("103", "Savings account", "Fee problem",
                        "An unexplained monthly fee was removed from my savings account."),
                caseRecord("104", "Checking account", "Problem using a debit or ATM card",
                        "The ATM debited my account but did not dispense any cash."));
        EvaluationCorpus corpus = EvaluationCorpus.fromCases(cases);
        EvaluationRunner runner = new EvaluationRunner(corpus);
        EvaluationQuery query = new EvaluationQuery(
                "Q1",
                EvaluationQuery.Kind.COMPLAINT,
                new CategoryValues(
                        "Checking or savings account", "Checking account",
                        "Managing an account", "Deposits and withdrawals"),
                "Money left my checking account but the recipient never received the transfer.",
                "scenario");

        SimilarityEngine production = new SimilarityEngine();
        for (EvaluationConfig config : List.of(
                EvaluationConfig.baseline(),
                new EvaluationConfig(0.2, 0.275),
                new EvaluationConfig(0.7, 0.7))) {
            List<SimilarityResult> evaluationResult = runner.rank(query, config);
            List<SimilarityResult> productionResult = production.rank(
                    EvaluationRunner.queryInput(query),
                    cases,
                    5,
                    config.settings());

            assertThat(evaluationResult).hasSameSizeAs(productionResult);
            for (int index = 0; index < productionResult.size(); index++) {
                SimilarityResult expected = productionResult.get(index);
                SimilarityResult actual = evaluationResult.get(index);
                assertThat(actual.caseRecord().id()).isEqualTo(expected.caseRecord().id());
                assertThat(actual.score()).isEqualTo(expected.score());
                assertThat(actual.categoryScore()).isEqualTo(expected.categoryScore());
                assertThat(actual.textScore()).isEqualTo(expected.textScore());
                assertThat(actual.categoryExplanation()).isEqualTo(expected.categoryExplanation());
                assertThat(actual.sharedTerms()).isEqualTo(expected.sharedTerms());
            }
        }
    }

    private static CaseRecord caseRecord(
            String cfpbId, String subProduct, String subIssue, String narrative) {
        return new CaseRecord(
                EvaluationCaseIds.fromComplaintId(cfpbId),
                CaseRecord.Source.CFPB,
                new CategoryValues(
                        "Checking or savings account", subProduct,
                        "Managing an account", subIssue),
                narrative,
                null,
                null,
                null,
                Instant.EPOCH,
                Instant.EPOCH,
                new CaseRecord.Provenance(
                        UUID.randomUUID(), cfpbId, "https://example.test/a.zip",
                        "sample.csv", null, null, "checksum"),
                null,
                List.of());
    }
}
