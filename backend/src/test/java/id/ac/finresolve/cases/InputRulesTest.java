package id.ac.finresolve.cases;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.ac.finresolve.categories.CategoryValues;
import id.ac.finresolve.http.BusinessException;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class InputRulesTest {

    @Test
    void normalizesCategoryWhitespaceAndBlankValues() {
        assertThat(InputRules.normalizeCategory(" Checking   account ")).isEqualTo("Checking account");
        assertThat(InputRules.normalizeCategory("Checking\u00a0\t\r\naccount"))
                .isEqualTo("Checking account");
        assertThat(InputRules.normalizeCategory("   ")).isNull();
        assertThat(InputRules.normalizeCategory(null)).isNull();
    }

    @Test
    void acceptsAValidComplaintAndTrimsAnalystNarrative() {
        ComplaintInput input = new ComplaintInput(
                new CategoryValues(
                        "  Checking   account  ",
                        "  ",
                        " Managing an account ",
                        "\t"),
                "  My transfer fee was not refunded.  ",
                LocalDate.parse("2024-12-01"),
                "  Example Bank ",
                "  Closed with explanation  ");

        ComplaintInput normalized = InputRules.complaint(input);

        assertThat(normalized.categories())
                .isEqualTo(new CategoryValues(
                        "Checking account", null, "Managing an account", null));
        assertThat(normalized.narrative()).isEqualTo("My transfer fee was not refunded.");
        assertThat(normalized.receivedDate()).isEqualTo(LocalDate.parse("2024-12-01"));
        assertThat(normalized.company()).isEqualTo("Example Bank");
        assertThat(normalized.companyResponse()).isEqualTo("Closed with explanation");
    }

    @Test
    void importValidationPreservesRawNarrativeWhileNormalizingCategories() {
        String rawNarrative = "  First line of the complaint.\nSecond line about a transfer fee.  \n";
        ComplaintInput input = new ComplaintInput(
                new CategoryValues(
                        "  Checking   account  ",
                        "  ",
                        " Managing an account ",
                        "\t"),
                rawNarrative,
                LocalDate.parse("2024-12-01"),
                "  Example Bank ",
                "  Closed with explanation  ");

        ComplaintInput imported = InputRules.complaintForImport(input);
        ComplaintInput analyst = InputRules.complaint(input);

        assertThat(imported.narrative()).isEqualTo(rawNarrative);
        assertThat(analyst.narrative())
                .isEqualTo("First line of the complaint.\nSecond line about a transfer fee.");
        assertThat(imported.categories()).isEqualTo(analyst.categories());
        assertThat(imported.categories())
                .isEqualTo(new CategoryValues(
                        "Checking account", null, "Managing an account", null));
        assertThat(imported.company()).isEqualTo("Example Bank");
        assertThat(imported.companyResponse()).isEqualTo("Closed with explanation");
    }

    @Test
    void bothValidationPathsRejectNarrativesMeaninglessAfterTrim() {
        ComplaintInput input = new ComplaintInput(
                new CategoryValues("Checking account", null, "Managing an account", null),
                "  the 123 XX  ",
                null,
                null,
                null);

        assertThatThrownBy(() -> InputRules.complaint(input))
                .isInstanceOfSatisfying(BusinessException.class, failure ->
                        assertThat(failure.fieldErrors()).anySatisfy(problem -> {
                            assertThat(problem.field()).isEqualTo("narrative");
                            assertThat(problem.code()).isEqualTo("NO_MEANINGFUL_TOKENS");
                        }));
        assertThatThrownBy(() -> InputRules.complaintForImport(input))
                .isInstanceOfSatisfying(BusinessException.class, failure ->
                        assertThat(failure.fieldErrors()).anySatisfy(problem -> {
                            assertThat(problem.field()).isEqualTo("narrative");
                            assertThat(problem.code()).isEqualTo("NO_MEANINGFUL_TOKENS");
                        }));
    }

    @Test
    void optionalBlankValuesBecomeNull() {
        ComplaintInput input = new ComplaintInput(
                new CategoryValues("Checking account", "   ", "Managing an account", "\t\n"),
                "A refund was promised but never arrived.",
                null,
                "  ",
                "");

        ComplaintInput normalized = InputRules.complaint(input);

        assertThat(normalized.categories().subProduct()).isNull();
        assertThat(normalized.categories().subIssue()).isNull();
        assertThat(normalized.receivedDate()).isNull();
        assertThat(normalized.company()).isNull();
        assertThat(normalized.companyResponse()).isNull();
    }

    @Test
    void acceptsAnIndonesianHeldFundsQuestionForSearch() {
        ComplaintInput input = new ComplaintInput(
                new CategoryValues(
                        "Checking or savings account", "Checking account",
                        "Managing an account", null),
                "Bagaimana mengambil uang saya yang ditahan bank?",
                null, null, null);

        ComplaintInput normalized = InputRules.complaint(input);

        assertThat(normalized.narrative())
                .isEqualTo("Bagaimana mengambil uang saya yang ditahan bank?");
    }

    @Test
    void novelCategoriesAreAcceptedWithoutCatalogMembership() {
        ComplaintInput input = new ComplaintInput(
                new CategoryValues(
                        "A brand new product", "A brand new subproduct",
                        "A brand new issue", "A brand new subissue"),
                "The new product would not let me move my money.",
                null,
                null,
                null);

        assertThat(InputRules.complaint(input).categories()).isEqualTo(input.categories());
    }

    @Test
    void rejectsNarrativesWithoutMeaningfulTokens() {
        assertRejectedWithField(
                "the 123 XX",
                "narrative",
                "NO_MEANINGFUL_TOKENS");
        assertRejectedWithField(
                "a an 12 XXX x",
                "narrative",
                "NO_MEANINGFUL_TOKENS");
        assertRejectedWithField(
                "   ",
                "narrative",
                "REQUIRED");
    }

    @Test
    void narrativeLengthIsMeasuredInUnicodeCodePoints() {
        String fiftyThousandCodePoints = "\uD801\uDC00".repeat(50_000);
        assertThat(fiftyThousandCodePoints.length()).isEqualTo(100_000);

        ComplaintInput normalized = InputRules.complaint(input(fiftyThousandCodePoints));
        assertThat(normalized.narrative()).isEqualTo(fiftyThousandCodePoints);

        String fiftyThousandAndOne = "\uD801\uDC00".repeat(50_001);
        assertRejectedWithField(fiftyThousandAndOne, "narrative", "TOO_LONG");
    }

    @Test
    void rejectsLabelsAndCompanyResponseOverTheirLimits() {
        assertRejectedWithField(
                new ComplaintInput(
                        new CategoryValues("a".repeat(501), null, "Issue", null),
                        "A meaningful narrative",
                        null,
                        null,
                        null),
                "product",
                "TOO_LONG");
        assertRejectedWithField(
                new ComplaintInput(
                        new CategoryValues("Product", "a".repeat(501), "Issue", null),
                        "A meaningful narrative",
                        null,
                        null,
                        null),
                "subProduct",
                "TOO_LONG");
        assertRejectedWithField(
                new ComplaintInput(
                        new CategoryValues("Product", null, "a".repeat(501), null),
                        "A meaningful narrative",
                        null,
                        null,
                        null),
                "issue",
                "TOO_LONG");
        assertRejectedWithField(
                new ComplaintInput(
                        new CategoryValues("Product", null, "Issue", "a".repeat(501)),
                        "A meaningful narrative",
                        null,
                        null,
                        null),
                "subIssue",
                "TOO_LONG");
        assertRejectedWithField(
                new ComplaintInput(
                        new CategoryValues("Product", null, "Issue", null),
                        "A meaningful narrative",
                        null,
                        "a".repeat(501),
                        null),
                "company",
                "TOO_LONG");
        assertRejectedWithField(
                new ComplaintInput(
                        new CategoryValues("Product", null, "Issue", null),
                        "A meaningful narrative",
                        null,
                        null,
                        "a".repeat(2_001)),
                "companyResponse",
                "TOO_LONG");
    }

    @Test
    void acceptsValuesAtTheirExactLimits() {
        ComplaintInput input = new ComplaintInput(
                new CategoryValues(
                        "p".repeat(500), "s".repeat(500),
                        "i".repeat(500), "x".repeat(500)),
                "a".repeat(49_998) + " b",
                null,
                "c".repeat(500),
                "r".repeat(2_000));

        ComplaintInput normalized = InputRules.complaint(input);

        assertThat(normalized.categories().product()).hasSize(500);
        assertThat(normalized.companyResponse()).hasSize(2_000);
    }

    @Test
    void rejectsMissingRequiredShapeAndChildWithoutParent() {
        assertRejectedWithField(
                new ComplaintInput(
                        new CategoryValues(null, "Child", "Issue", null),
                        "A meaningful narrative",
                        null,
                        null,
                        null),
                "product",
                "REQUIRED");
        assertRejectedWithField(
                new ComplaintInput(
                        new CategoryValues("Product", "Child", null, null),
                        "A meaningful narrative",
                        null,
                        null,
                        null),
                "issue",
                "REQUIRED");
        assertRejectedWithField(
                new ComplaintInput(
                        new CategoryValues(null, "Child", null, "Grandchild"),
                        "A meaningful narrative",
                        null,
                        null,
                        null),
                "subProduct",
                "REQUIRED");
        assertRejectedWithField(
                new ComplaintInput(
                        new CategoryValues(null, null, null, "Grandchild"),
                        "A meaningful narrative",
                        null,
                        null,
                        null),
                "subIssue",
                "REQUIRED");
    }

    @Test
    void rejectsNullCategoriesAndNullComplaintWithoutLeakingNarrative() {
        assertThatThrownBy(() -> InputRules.complaint(null))
                .isInstanceOfSatisfying(BusinessException.class, failure ->
                        assertThat(failure.status()).isEqualTo(400));

        BusinessException failure = failureFor(new ComplaintInput(null, "sensitive narrative", null, null, null));
        assertThat(failure.fieldErrors())
                .extracting(BusinessException.Problem::field)
                .contains("categories");
        assertThat(failure.getMessage()).doesNotContain("sensitive narrative");
        assertThat(failure.fieldErrors())
                .allSatisfy(problem -> assertThat(problem.message()).doesNotContain("sensitive narrative"));
    }

    private ComplaintInput input(String narrative) {
        return new ComplaintInput(
                new CategoryValues("Checking account", null, "Managing an account", null),
                narrative,
                null,
                null,
                null);
    }

    private void assertRejectedWithField(String narrative, String field, String code) {
        assertRejectedWithField(input(narrative), field, code);
    }

    private void assertRejectedWithField(ComplaintInput input, String field, String code) {
        BusinessException failure = failureFor(input);
        assertThat(failure.status()).isEqualTo(400);
        assertThat(failure.fieldErrors())
                .anySatisfy(problem -> {
                    assertThat(problem.field()).isEqualTo(field);
                    assertThat(problem.code()).isEqualTo(code);
                });
    }

    private BusinessException failureFor(ComplaintInput input) {
        try {
            InputRules.complaint(input);
        } catch (BusinessException failure) {
            return failure;
        }
        throw new AssertionError("Expected a BusinessException");
    }
}
