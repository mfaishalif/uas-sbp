package id.ac.finresolve.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EvaluationQueriesTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void loadsTheCommittedV2QueriesWithTenColumnsAndFixedQuoting() {
        List<EvaluationQuery> queries = EvaluationQueries.load(
                Path.of("../docs/evaluation/v2/queries.csv"));

        assertThat(queries).hasSize(104);
        assertThat(queries.stream()
                .filter(query -> query.kind() == EvaluationQuery.Kind.COMPLAINT))
                .hasSize(64);
        assertThat(queries.stream()
                .filter(query -> query.kind() == EvaluationQuery.Kind.NEGATIVE))
                .hasSize(40);
        assertThat(queries.stream()
                .filter(query -> query.cohort() == EvaluationQuery.Cohort.STUDY)
                .map(EvaluationQuery::groupId)
                .distinct())
                .hasSize(40);
        EvaluationQuery q21 = queries.stream()
                .filter(query -> query.queryId().equals("Q21"))
                .findFirst()
                .orElseThrow();
        EvaluationQuery q22 = queries.stream()
                .filter(query -> query.queryId().equals("Q22"))
                .findFirst()
                .orElseThrow();
        assertThat(q21.scenario())
                .isEqualTo("Payroll question sharing funds, deposit, and low-balance words");
        assertThat(q22.scenario())
                .isEqualTo("Phishing text sharing fraud, scam, text, and delayed words");
        assertThat(q21.narrative()).contains("payroll schedule");
        assertThat(q22.narrative()).contains("package delivery");
    }

    @Test
    void rejectsAQueryRowWithAnExtraUnquotedField() throws Exception {
        Path malformed = temporaryDirectory.resolve("queries.csv");
        Files.writeString(malformed, """
                queryId,kind,groupId,cohort,product,subProduct,issue,subIssue,narrative,scenario
                Q1,complaint,G1,study,Product,,Issue,,"Narrative",Scenario, extra
                """, StandardCharsets.UTF_8);

        assertThatThrownBy(() -> EvaluationQueries.load(malformed))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("columns");
    }

    @Test
    void rejectsDuplicateQueryIds() throws Exception {
        Path duplicate = temporaryDirectory.resolve("duplicate.csv");
        Files.writeString(duplicate, """
                queryId,kind,groupId,cohort,product,subProduct,issue,subIssue,narrative,scenario
                Q1,complaint,G1,study,Product,,Issue,,"Narrative",Scenario
                Q1,complaint,G2,study,Product,,Issue,,"Narrative",Scenario
                """, StandardCharsets.UTF_8);

        assertThatThrownBy(() -> EvaluationQueries.load(duplicate))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate query ID");
    }

    @Test
    void rejectsAStudyGroupWhoseParaphrasesHaveDifferentKinds() throws Exception {
        Path mixed = temporaryDirectory.resolve("mixed.csv");
        Files.writeString(mixed, """
                queryId,kind,groupId,cohort,product,subProduct,issue,subIssue,narrative,scenario
                Q1,complaint,G1,study,Product,,Issue,,"Narrative",Scenario
                Q2,negative,G1,study,Product,,Issue,,"Narrative",Scenario
                """, StandardCharsets.UTF_8);

        assertThatThrownBy(() -> EvaluationQueries.load(mixed))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mixes query kinds");
    }
}
