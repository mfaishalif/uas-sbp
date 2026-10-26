package id.ac.finresolve.http;

import static org.assertj.core.api.Assertions.assertThat;

import id.ac.finresolve.api.model.RetainCaseRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CodePointSizeValidationTest {

    private final Validator validator =
            Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void supplementaryCharactersAreValidatedByCodePoint() {
        String narrative = "transfer " + "\uD83D\uDE00".repeat(25_000);

        Set<ConstraintViolation<RetainCaseRequest>> violations =
                validator.validate(request(narrative));

        assertThat(narrative.codePointCount(0, narrative.length())).isEqualTo(25_009);
        assertThat(violations)
                .noneMatch(violation -> violation.getPropertyPath().toString().equals("narrative"));
    }

    @Test
    void overLimitTextStillProducesANarrativeViolation() {
        Set<ConstraintViolation<RetainCaseRequest>> violations =
                validator.validate(request("a".repeat(50_001)));

        assertThat(violations)
                .anyMatch(violation -> violation.getPropertyPath().toString().equals("narrative"));
    }

    private static RetainCaseRequest request(String narrative) {
        return new RetainCaseRequest(
                "Checking or savings account",
                "Managing an account",
                narrative,
                "Reviewed transfer evidence.",
                RetainCaseRequest.OutcomeAssessmentEnum.UNKNOWN);
    }
}
