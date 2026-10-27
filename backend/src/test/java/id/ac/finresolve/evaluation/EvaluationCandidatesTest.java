package id.ac.finresolve.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

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

class EvaluationCandidatesTest {

    private static final CategoryValues CATEGORIES =
            new CategoryValues("Product", null, "Issue", null);

    @TempDir
    Path temporaryDirectory;

    @Test
    void packetContainsOnlyPairIdentityNarrativesAndCategories() throws Exception {
        EvaluationQuery query = new EvaluationQuery(
                "Q1", EvaluationQuery.Kind.NEGATIVE, CATEGORIES,
                "query narrative text", "scenario that must stay hidden");
        EvaluationCorpus corpus = EvaluationCorpus.fromCases(List.of(caseRecord("101")));
        List<EvaluationLabels.Pair> pairs =
                List.of(new EvaluationLabels.Pair("Q1", "101"));

        String checksum = EvaluationCandidates.writePackets(
                temporaryDirectory, List.of(query), corpus, pairs, 25);

        Path packet = temporaryDirectory.resolve("batch-001.csv");
        assertThat(packet).exists();
        String header = Files.readAllLines(packet, StandardCharsets.UTF_8).get(0);
        assertThat(header.split(",")).containsExactly(
                "queryId", "cfpbId", "queryNarrative",
                "queryProduct", "querySubProduct", "queryIssue", "querySubIssue",
                "caseNarrative", "caseProduct", "caseSubProduct", "caseIssue", "caseSubIssue");
        assertThat(header).doesNotContain(
                "kind", "split", "score", "rank", "configuration", "scenario");
        assertThat(checksum).matches("[a-f0-9]{64}");
    }

    @Test
    void packetChecksumIsDeterministic() {
        EvaluationQuery query = new EvaluationQuery(
                "Q1", EvaluationQuery.Kind.COMPLAINT, CATEGORIES,
                "query narrative text", "scenario");
        EvaluationCorpus corpus = EvaluationCorpus.fromCases(List.of(caseRecord("101")));
        List<EvaluationLabels.Pair> pairs =
                List.of(new EvaluationLabels.Pair("Q1", "101"));

        String first = EvaluationCandidates.writePackets(
                temporaryDirectory, List.of(query), corpus, pairs, 25);
        String second = EvaluationCandidates.writePackets(
                temporaryDirectory, List.of(query), corpus, pairs, 25);

        assertThat(first).isEqualTo(second);
    }

    private static CaseRecord caseRecord(String cfpbId) {
        return new CaseRecord(
                UUID.nameUUIDFromBytes(cfpbId.getBytes(StandardCharsets.UTF_8)),
                CaseRecord.Source.CFPB,
                CATEGORIES,
                "case narrative text",
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
