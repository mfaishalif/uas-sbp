package id.ac.finresolve.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import id.ac.finresolve.categories.CategoryValues;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EvaluationSplitTest {

    @Test
    void splitsStratifiedCountsDeterministically() {
        List<EvaluationQuery> queries = syntheticQueries();

        EvaluationSplit first = EvaluationSplit.create(
                queries, EvaluationSplit.SEED, 10, 4);
        EvaluationSplit second = EvaluationSplit.create(
                queries, EvaluationSplit.SEED, 10, 4);

        assertThat(first.asMap()).isEqualTo(second.asMap());
        assertThat(count(first, EvaluationQuery.Kind.COMPLAINT, EvaluationSplit.Part.CALIBRATION))
                .isEqualTo(10);
        assertThat(count(first, EvaluationQuery.Kind.COMPLAINT, EvaluationSplit.Part.TEST))
                .isEqualTo(6);
        assertThat(count(first, EvaluationQuery.Kind.NEGATIVE, EvaluationSplit.Part.CALIBRATION))
                .isEqualTo(4);
        assertThat(count(first, EvaluationQuery.Kind.NEGATIVE, EvaluationSplit.Part.TEST))
                .isEqualTo(4);
        assertThat(first.count(EvaluationSplit.Part.CALIBRATION)).isEqualTo(14);
        assertThat(first.count(EvaluationSplit.Part.TEST)).isEqualTo(10);
    }

    @Test
    void everyQueryReceivesExactlyOnePart() {
        List<EvaluationQuery> queries = syntheticQueries();
        EvaluationSplit split = EvaluationSplit.create(queries);

        for (EvaluationQuery query : queries) {
            assertThat(split.partOf(query.queryId())).isNotNull();
        }
        assertThat(split.asMap()).hasSize(queries.size());
    }

    @Test
    void differentSeedChangesTheAssignment() {
        List<EvaluationQuery> queries = syntheticQueries();

        assertThat(EvaluationSplit.create(queries, 1L, 10, 4).asMap())
                .isNotEqualTo(EvaluationSplit.create(queries, 2L, 10, 4).asMap());
    }

    @Test
    void freezesTheCommittedQuerySetAndSplit() {
        List<EvaluationQuery> queries = EvaluationQueries.load(
                java.nio.file.Path.of("../docs/evaluation/queries.csv"));
        EvaluationSplit expected = EvaluationSplit.create(queries);

        EvaluationSplitFile.verify(
                java.nio.file.Path.of("../docs/evaluation/split.csv"),
                queries,
                expected);

        assertThat(queries).hasSize(24);
        assertThat(queries.stream()
                .filter(query -> query.kind() == EvaluationQuery.Kind.COMPLAINT))
                .hasSize(16);
        assertThat(queries.stream()
                .filter(query -> query.kind() == EvaluationQuery.Kind.NEGATIVE))
                .hasSize(8);
    }

    private static int count(
            EvaluationSplit split,
            EvaluationQuery.Kind kind,
            EvaluationSplit.Part part) {
        return (int) split.asMap().entrySet().stream()
                .filter(entry -> entry.getValue() == part)
                .filter(entry -> entry.getKey().startsWith(kind == EvaluationQuery.Kind.COMPLAINT
                        ? "C"
                        : "N"))
                .count();
    }

    private static List<EvaluationQuery> syntheticQueries() {
        List<EvaluationQuery> queries = new ArrayList<>();
        for (int index = 1; index <= 16; index++) {
            queries.add(query("C%02d".formatted(index), EvaluationQuery.Kind.COMPLAINT));
        }
        for (int index = 1; index <= 8; index++) {
            queries.add(query("N%02d".formatted(index), EvaluationQuery.Kind.NEGATIVE));
        }
        return queries;
    }

    private static EvaluationQuery query(String id, EvaluationQuery.Kind kind) {
        return new EvaluationQuery(
                id,
                kind,
                new CategoryValues("Product", null, "Issue", null),
                "narrative",
                "scenario");
    }
}
