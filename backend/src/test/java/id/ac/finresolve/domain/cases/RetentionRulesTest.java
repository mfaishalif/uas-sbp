package id.ac.finresolve.domain.cases;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import id.ac.finresolve.domain.category.CategoryValues;
import id.ac.finresolve.domain.error.BusinessException;
import id.ac.finresolve.support.HttpStatusOf;
import java.util.List;
import org.junit.jupiter.api.Test;

class RetentionRulesTest {

    private static final ComplaintInput COMPLAINT = new ComplaintInput(
            new CategoryValues("Product", null, "Issue", null),
            "A meaningful narrative about a transfer.",
            null,
            null,
            null);

    @Test
    void rejectsNulAndUnpairedSurrogatesInHandlingNotes() {
        for (String value : List.of("\u0000notes", "notes\u0000middle", "notes\u0000")) {
            assertRejected(command(value, null), "handlingNotes");
        }
        assertRejected(command("notes\uD800", null), "handlingNotes");
        assertRejected(command("notes\uDC00", null), "handlingNotes");
    }

    @Test
    void rejectsNulAndUnpairedSurrogatesInEvaluationNotes() {
        for (String value : List.of("\u0000eval", "eval\u0000middle", "eval\u0000")) {
            assertRejected(command("Notes.", value), "evaluationNotes");
        }
        assertRejected(command("Notes.", "eval\uD800"), "evaluationNotes");
        assertRejected(command("Notes.", "eval\uDC00"), "evaluationNotes");
    }

    @Test
    void keepsValidSupplementaryCharactersTabsAndNewlines() {
        String handling = "Reviewed 😀\twith tab.\nSecond line.";
        String evaluation = "Decision 𝄞\nSecond line.";

        RetentionRules.Validated validated = RetentionRules.validate(command(handling, evaluation));

        assertThat(validated.notes().handlingNotes()).isEqualTo(handling);
        assertThat(validated.notes().evaluationNotes()).isEqualTo(evaluation);
    }

    private static RetainCommand command(String handlingNotes, String evaluationNotes) {
        return new RetainCommand(
                COMPLAINT,
                new CaseRecord.Notes(
                        handlingNotes, CaseRecord.Outcome.UNKNOWN, evaluationNotes),
                List.of());
    }

    private static void assertRejected(RetainCommand command, String field) {
        BusinessException failure = catchThrowableOfType(
                () -> RetentionRules.validate(command), BusinessException.class);
        assertThat(failure).isNotNull();
        assertThat(failure.code()).isEqualTo("VALIDATION_ERROR");
        assertThat(HttpStatusOf.of(failure)).isEqualTo(400);
        assertThat(failure.fieldErrors()).anySatisfy(problem -> {
            assertThat(problem.field()).isEqualTo(field);
            assertThat(problem.code()).isEqualTo("INVALID_FORMAT");
        });
        assertThat(failure.getMessage()).doesNotContain("notes");
        assertThat(failure.fieldErrors())
                .allSatisfy(problem -> assertThat(problem.message()).doesNotContain("notes"));
    }
}
