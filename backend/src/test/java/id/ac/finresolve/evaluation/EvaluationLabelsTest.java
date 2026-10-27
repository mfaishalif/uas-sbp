package id.ac.finresolve.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.ac.finresolve.domain.cases.CaseRecord;
import id.ac.finresolve.domain.category.CategoryValues;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EvaluationLabelsTest {

    private static final CategoryValues CATEGORIES =
            new CategoryValues("Product", null, "Issue", null);
    private static final List<EvaluationLabels.Pair> PAIRS =
            List.of(new EvaluationLabels.Pair("Q1", "101"));

    @TempDir
    Path temporaryDirectory;

    @Test
    void rejectsDuplicateJudgments() {
        assertThatThrownBy(() -> EvaluationLabels.of(List.of(
                        judgment("Q1", "101", true),
                        judgment("Q1", "101", false))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate judgment");
    }

    @Test
    void failsInsteadOfTreatingMissingLabelsAsIrrelevant() {
        EvaluationLabels labels = EvaluationLabels.of(List.of(judgment("Q1", "101", true)));

        assertThatThrownBy(() -> labels.requireCoverage(List.of(
                        new EvaluationLabels.Pair("Q1", "101"),
                        new EvaluationLabels.Pair("Q1", "102"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Missing judgments")
                .hasMessageContaining("Q1/102");
        assertThatThrownBy(() -> labels.relevant("Q1", "102"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Missing judgment");
    }

    @Test
    void acceptsAgreedReviewsWithoutAnAdjudication() throws Exception {
        LabelFixture fixture = new LabelFixture();
        fixture.reviewA("1");
        fixture.reviewB("1");
        fixture.finalLabel("1", "reviewer-a+reviewer-b");

        EvaluationLabelsFile.ValidatedLabels validated = fixture.read();

        assertThat(validated.labels().size()).isEqualTo(1);
        assertThat(validated.labels().relevant("Q1", "101")).isTrue();
        assertThat(validated.agreement().agreements()).isEqualTo(1);
        assertThat(validated.agreement().agreementRate()).isEqualTo(1.0);
        assertThat(validated.agreement().cohensKappa()).isNull();
    }

    @Test
    void requiresAndUsesAnAdjudicationForEveryDisagreement() throws Exception {
        LabelFixture fixture = new LabelFixture();
        fixture.reviewA("1");
        fixture.reviewB("0");
        fixture.adjudication("1");
        fixture.finalLabel("1", "adjudicator");

        EvaluationLabelsFile.ValidatedLabels validated = fixture.read();

        assertThat(validated.labels().relevant("Q1", "101")).isTrue();
        assertThat(validated.agreement().disagreements()).isEqualTo(1);
        assertThat(validated.agreement().cohensKappa()).isZero();
    }

    @Test
    void failsWhenADisagreementHasNoAdjudication() throws Exception {
        LabelFixture fixture = new LabelFixture();
        fixture.reviewA("1");
        fixture.reviewB("0");
        fixture.finalLabel("1", "reviewer-a");

        assertThatThrownBy(fixture::read)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unadjudicated disagreement");
    }

    @Test
    void failsOnDuplicateFinalLabels() throws Exception {
        LabelFixture fixture = new LabelFixture();
        fixture.reviewA("1");
        fixture.reviewB("1");
        fixture.writeFinal("""
                queryId,cfpbId,relevant,reason,evidence,reviewer
                Q1,101,1,Reason.,case narrative,reviewer-a
                Q1,101,1,Reason.,case narrative,reviewer-b
                """);

        assertThatThrownBy(fixture::read)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate judgment");
    }

    @Test
    void failsWhenEvidenceIsNotAVerbatimNarrativeQuotation() throws Exception {
        LabelFixture fixture = new LabelFixture();
        fixture.reviewA("1");
        fixture.reviewB("1");
        fixture.finalLabel("1", "reviewer-a+reviewer-b");
        fixture.writeReview("a", "1", "invented quotation");

        assertThatThrownBy(fixture::read)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a verbatim");
    }

    @Test
    void rejectsMalformedBinaryLabels() throws Exception {
        LabelFixture fixture = new LabelFixture();
        fixture.reviewA("maybe");
        fixture.reviewB("1");
        fixture.finalLabel("1", "reviewer-a+reviewer-b");

        assertThatThrownBy(fixture::read)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be 0 or 1");
    }

    private static EvaluationLabels.Judgment judgment(
            String queryId, String cfpbId, boolean relevant) {
        return new EvaluationLabels.Judgment(
                queryId, cfpbId, relevant, "reason", "case narrative", "reviewer-a");
    }

    private static CaseRecord caseRecord(String cfpbId) {
        return new CaseRecord(
                UUID.nameUUIDFromBytes(cfpbId.getBytes(StandardCharsets.UTF_8)),
                CaseRecord.Source.CFPB,
                CATEGORIES,
                "case narrative",
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

    private final class LabelFixture {

        private final List<EvaluationQuery> queries = List.of(new EvaluationQuery(
                "Q1", EvaluationQuery.Kind.COMPLAINT, CATEGORIES,
                "query narrative", "scenario"));
        private final EvaluationCorpus corpus =
                EvaluationCorpus.fromCases(List.of(caseRecord("101")));
        private final Path labels = temporaryDirectory;

        private LabelFixture() throws Exception {
            Files.createDirectories(labels);
        }

        private void reviewA(String relevant) throws Exception {
            writeReview("a", relevant, "case narrative");
        }

        private void reviewB(String relevant) throws Exception {
            writeReview("b", relevant, "case narrative");
        }

        private void writeReview(String reviewer, String relevant, String evidence)
                throws Exception {
            write("review-" + reviewer + ".csv", """
                    queryId,cfpbId,relevant,reason,evidence,reviewer
                    Q1,101,%s,Specific reason.,%s,reviewer-%s
                    """.formatted(relevant, evidence, reviewer));
        }

        private void adjudication(String relevant) throws Exception {
            write("adjudication.csv", """
                    queryId,cfpbId,reviewerARelevant,reviewerBRelevant,adjudicator,relevant,reason,evidence
                    Q1,101,1,0,adjudicator,%s,Adjudicated reason.,case narrative
                    """.formatted(relevant));
        }

        private void finalLabel(String relevant, String reviewer) throws Exception {
            writeFinal("""
                    queryId,cfpbId,relevant,reason,evidence,reviewer
                    Q1,101,%s,Final reason.,case narrative,%s
                    """.formatted(relevant, reviewer));
        }

        private void writeFinal(String content) throws Exception {
            write("final.csv", content);
        }

        private void write(String fileName, String content) throws Exception {
            Files.writeString(
                    labels.resolve(fileName), content, StandardCharsets.UTF_8);
            if (!Files.exists(labels.resolve("adjudication.csv"))) {
                writeEmptyAdjudication();
            }
        }

        private void writeEmptyAdjudication() throws Exception {
            Files.writeString(
                    labels.resolve("adjudication.csv"),
                    "queryId,cfpbId,reviewerARelevant,reviewerBRelevant,"
                            + "adjudicator,relevant,reason,evidence\n",
                    StandardCharsets.UTF_8);
        }

        private EvaluationLabelsFile.ValidatedLabels read() {
            return EvaluationLabelsFile.readAndAudit(
                    new EvaluationManifest.LabelPaths(
                            labels.resolve("final.csv"),
                            labels.resolve("review-a.csv"),
                            labels.resolve("review-b.csv"),
                            labels.resolve("adjudication.csv")),
                    queries,
                    corpus,
                    PAIRS);
        }
    }
}
