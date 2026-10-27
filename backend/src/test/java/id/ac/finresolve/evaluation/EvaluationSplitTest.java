package id.ac.finresolve.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.ac.finresolve.domain.category.CategoryValues;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EvaluationSplitTest {

    private static final long SEED = 20261027L;

    @TempDir
    Path temporaryDirectory;

    @Test
    void splitsStudyGroupsWithoutLeakageAndKeepsDevelopmentInCalibration() {
        List<EvaluationQuery> queries = syntheticStudy();
        queries.add(query("D1", EvaluationQuery.Kind.COMPLAINT,
                "dev-complaint", EvaluationQuery.Cohort.DEVELOPMENT));
        queries.add(query("D2", EvaluationQuery.Kind.NEGATIVE,
                "dev-negative", EvaluationQuery.Cohort.DEVELOPMENT));

        EvaluationSplit split = EvaluationSplit.create(
                queries, new EvaluationSplit.SplitSpec(SEED, 24, 16));

        assertThat(split.count(EvaluationSplit.Part.CALIBRATION)).isEqualTo(42);
        assertThat(split.count(EvaluationSplit.Part.TEST)).isEqualTo(40);
        assertThat(split.count(
                        EvaluationSplit.Part.CALIBRATION,
                        EvaluationQuery.Kind.COMPLAINT, queries))
                .isEqualTo(25);
        assertThat(split.count(
                        EvaluationSplit.Part.CALIBRATION,
                        EvaluationQuery.Kind.NEGATIVE, queries))
                .isEqualTo(17);
        assertThat(split.partOf("D1")).isEqualTo(EvaluationSplit.Part.CALIBRATION);
        assertThat(split.partOf("D2")).isEqualTo(EvaluationSplit.Part.CALIBRATION);
        assertGroupPartsAreStable(queries, split);
    }

    @Test
    void differentSeedChangesTheAssignment() {
        List<EvaluationQuery> queries = syntheticStudy();

        EvaluationSplit first = EvaluationSplit.create(
                queries, new EvaluationSplit.SplitSpec(1L, 24, 16));
        EvaluationSplit second = EvaluationSplit.create(
                queries, new EvaluationSplit.SplitSpec(2L, 24, 16));

        assertThat(first.asMap()).isNotEqualTo(second.asMap());
    }

    @Test
    void committedV2StudyMatchesManifestCountsAndSplit() {
        EvaluationManifest manifest = EvaluationManifest.load(
                Path.of("../docs/evaluation/v2/manifest.json"));
        List<EvaluationQuery> queries = EvaluationQueries.load(manifest.queries());
        EvaluationSplit expected = EvaluationSplit.create(queries, manifest.splitSpec());

        EvaluationSplitFile.verify(manifest.split(), queries, expected);

        assertThat(queries).hasSize(manifest.expected().total());
        assertThat(queries.stream()
                .filter(query -> query.kind() == EvaluationQuery.Kind.COMPLAINT))
                .hasSize(manifest.expected().complaints());
        assertThat(queries.stream()
                .filter(query -> query.kind() == EvaluationQuery.Kind.NEGATIVE))
                .hasSize(manifest.expected().negatives());
        assertThat(expected.count(EvaluationSplit.Part.CALIBRATION))
                .isEqualTo(manifest.expected().calibrationTotal());
        assertThat(expected.count(EvaluationSplit.Part.TEST))
                .isEqualTo(manifest.expected().testTotal());
        assertThat(queries.stream()
                .filter(query -> query.cohort() == EvaluationQuery.Cohort.STUDY)
                .map(EvaluationQuery::groupId)
                .distinct()
                .count())
                .isEqualTo(manifest.expected().studyGroups());
        assertGroupPartsAreStable(queries, expected);
    }

    @Test
    void rejectsATamperedSplitThatMovesOneParaphrase() throws Exception {
        List<EvaluationQuery> queries = syntheticStudy();
        EvaluationSplit expected = EvaluationSplit.create(
                queries, new EvaluationSplit.SplitSpec(SEED, 24, 16));
        Path splitFile = temporaryDirectory.resolve("split.csv");
        EvaluationSplitFile.write(splitFile, queries, expected);

        EvaluationQuery moved = queries.stream()
                .filter(query -> query.kind() == EvaluationQuery.Kind.COMPLAINT)
                .filter(query -> expected.partOf(query.queryId())
                        == EvaluationSplit.Part.CALIBRATION)
                .findFirst()
                .orElseThrow();
        List<String> lines = new ArrayList<>(
                Files.readAllLines(splitFile, StandardCharsets.UTF_8));
        for (int index = 1; index < lines.size(); index++) {
            if (lines.get(index).startsWith(moved.queryId() + ",")) {
                lines.set(index, moved.queryId() + ",complaint,test");
                break;
            }
        }
        Files.write(splitFile, lines, StandardCharsets.UTF_8);

        assertThatThrownBy(() -> EvaluationSplitFile.verify(splitFile, queries, expected))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("grouped seed");
    }

    private static void assertGroupPartsAreStable(
            List<EvaluationQuery> queries, EvaluationSplit split) {
        Map<String, EvaluationSplit.Part> groupParts = new LinkedHashMap<>();
        for (EvaluationQuery query : queries) {
            EvaluationSplit.Part existing =
                    groupParts.putIfAbsent(query.groupId(), split.partOf(query.queryId()));
            if (existing != null) {
                assertThat(split.partOf(query.queryId())).isEqualTo(existing);
            }
        }
    }

    private static List<EvaluationQuery> syntheticStudy() {
        List<EvaluationQuery> queries = new ArrayList<>();
        for (int index = 1; index <= 24; index++) {
            String group = "C%02d".formatted(index);
            queries.add(query("C%02dA".formatted(index),
                    EvaluationQuery.Kind.COMPLAINT, group, EvaluationQuery.Cohort.STUDY));
            queries.add(query("C%02dB".formatted(index),
                    EvaluationQuery.Kind.COMPLAINT, group, EvaluationQuery.Cohort.STUDY));
        }
        for (int index = 1; index <= 16; index++) {
            String group = "N%02d".formatted(index);
            queries.add(query("N%02dA".formatted(index),
                    EvaluationQuery.Kind.NEGATIVE, group, EvaluationQuery.Cohort.STUDY));
            queries.add(query("N%02dB".formatted(index),
                    EvaluationQuery.Kind.NEGATIVE, group, EvaluationQuery.Cohort.STUDY));
        }
        return queries;
    }

    private static EvaluationQuery query(
            String id,
            EvaluationQuery.Kind kind,
            String group,
            EvaluationQuery.Cohort cohort) {
        return new EvaluationQuery(
                id,
                kind,
                group,
                cohort,
                new CategoryValues("Product", null, "Issue", null),
                "narrative",
                "scenario");
    }
}
