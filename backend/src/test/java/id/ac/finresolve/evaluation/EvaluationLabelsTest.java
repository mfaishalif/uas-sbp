package id.ac.finresolve.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.ac.finresolve.cases.CaseRecord;
import id.ac.finresolve.categories.CategoryValues;
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
    void readsCompleteLabelsAndRejectsMalformedRelevance() throws Exception {
        List<EvaluationQuery> queries = List.of(
                new EvaluationQuery(
                        "Q1", EvaluationQuery.Kind.COMPLAINT, CATEGORIES,
                        "query narrative", "scenario"));
        EvaluationCorpus corpus = EvaluationCorpus.fromCases(List.of(caseRecord("101")));

        Path valid = temporaryDirectory.resolve("valid.csv");
        Files.writeString(valid, """
                queryId,cfpbId,relevant,reason
                Q1,101,1,Useful context.
                """, StandardCharsets.UTF_8);
        EvaluationLabels labels = EvaluationLabelsFile.read(valid, queries, corpus);
        assertThat(labels.relevant("Q1", "101")).isTrue();
        assertThat(labels.reason("Q1", "101")).isEqualTo("Useful context.");

        Path invalid = temporaryDirectory.resolve("invalid.csv");
        Files.writeString(invalid, """
                queryId,cfpbId,relevant,reason
                Q1,101,maybe,Reason.
                """, StandardCharsets.UTF_8);
        assertThatThrownBy(() -> EvaluationLabelsFile.read(invalid, queries, corpus))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("relevant must be 0 or 1");

        Path unknown = temporaryDirectory.resolve("unknown.csv");
        Files.writeString(unknown, """
                queryId,cfpbId,relevant,reason
                Q9,101,1,Reason.
                """, StandardCharsets.UTF_8);
        assertThatThrownBy(() -> EvaluationLabelsFile.read(unknown, queries, corpus))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown query");
    }

    private static EvaluationLabels.Judgment judgment(
            String queryId, String cfpbId, boolean relevant) {
        return new EvaluationLabels.Judgment(queryId, cfpbId, relevant, "reason");
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
}
