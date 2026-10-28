package id.ac.finresolve.presentation.validation;

import id.ac.finresolve.domain.error.BusinessException;
import id.ac.finresolve.support.HttpStatusOf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import id.ac.finresolve.domain.error.BusinessException.Problem;
import java.io.InputStream;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Direct tests for the contract-driven rules that guard request bodies before binding. */
class RequestRulesTest {

    private static final RequestRules RULES = new RequestRules();

    private static final String RETAIN_CASE = """
            {
              "product": "Checking or savings account",
              "subProduct": "Checking account",
              "issue": "Managing an account",
              "subIssue": null,
              "narrative": "My transfer is missing from my checking account.",
              "receivedDate": null,
              "company": null,
              "companyResponse": null,
              "handlingNotes": "Reviewed transfer evidence and requested a trace.",
              "outcomeAssessment": "UNKNOWN",
              "evaluationNotes": null,
              "referenceCaseIds": []
            }
            """;

    private static final String SEARCH = """
            {
              "product": "Checking or savings account",
              "subProduct": null,
              "issue": "Managing an account",
              "subIssue": null,
              "narrative": "My transfer is missing from my checking account.",
              "limit": 5
            }
            """;

    @Test
    void theClasspathContractProvidesTheRequestSchemasUsedByTheRules() throws Exception {
        try (InputStream contract = RequestRules.class.getResourceAsStream(RequestRules.CONTRACT_RESOURCE)) {
            assertThat(contract).as("docs/api/openapi.json must be on the classpath").isNotNull();
        }

        assertThat(RULES.hasSchema("RetainCaseRequest")).isTrue();
        assertThat(RULES.hasSchema("SearchRequest")).isTrue();
        assertThat(RULES.hasSchema("NotAContractSchema")).isFalse();
    }

    @Test
    void explicitNullForANonNullableLimitIsRejected() {
        BusinessException failure = rejected("SearchRequest", replace(SEARCH, "\"limit\": 5", "\"limit\": null"));

        assertThat(HttpStatusOf.of(failure)).isEqualTo(400);
        assertThat(failure.code()).isEqualTo("VALIDATION_ERROR");
        assertThat(fields(failure)).containsExactly("limit");
    }

    @Test
    void explicitNullForANonNullableReferenceListIsRejected() {
        BusinessException failure = rejected(
                "RetainCaseRequest",
                replace(RETAIN_CASE, "\"referenceCaseIds\": []", "\"referenceCaseIds\": null"));

        assertThat(HttpStatusOf.of(failure)).isEqualTo(400);
        assertThat(fields(failure)).containsExactly("referenceCaseIds");
    }

    @Test
    void nullableContractPropertiesStillAcceptExplicitNull() {
        // subProduct, subIssue, receivedDate, company, companyResponse, and evaluationNotes are
        // nullable in the source contract and this body already sets every one of them to null.
        RULES.validateJson("RetainCaseRequest", RETAIN_CASE);
    }

    @Test
    void unknownFieldsAreRejectedWithTheNestedContractPath() {
        BusinessException failure = rejected(
                "RetainCaseRequest", replace(RETAIN_CASE, "{", "{\n  \"source\": \"ANALYST\","));

        assertThat(fields(failure)).containsExactly("source");
        assertThat(failure.fieldErrors())
                .extracting(Problem::code)
                .containsExactly("UNKNOWN_FIELD");
    }

    @Test
    void missingRequiredFieldsAreRejected() {
        String missing = replace(
                RETAIN_CASE,
                "\"handlingNotes\": \"Reviewed transfer evidence and requested a trace.\",",
                "");

        BusinessException failure = rejected("RetainCaseRequest", missing);

        assertThat(fields(failure)).containsExactly("handlingNotes");
        assertThat(failure.fieldErrors())
                .extracting(Problem::code)
                .containsExactly("REQUIRED");
    }

    @Test
    void rawMaximumLengthIsCheckedBeforeTrimOrCollapse() {
        String rawTooLong = replace(
                RETAIN_CASE,
                "\"narrative\": \"My transfer is missing from my checking account.\"",
                "\"narrative\": \"" + "x" + " ".repeat(50_000) + "\"");

        BusinessException failure = rejected("RetainCaseRequest", rawTooLong);

        assertThat(fields(failure)).containsExactly("narrative");
        assertThat(failure.fieldErrors())
                .extracting(Problem::code)
                .containsExactly("TOO_LONG");
    }

    @Test
    void abbreviatedAndMalformedUuidsAreRejected() {
        for (String id : List.of("1-1-1-1-1", "not-a-uuid", "11111111-1111-4111-8111-11111111111")) {
            String body = replace(
                    RETAIN_CASE,
                    "\"referenceCaseIds\": []",
                    "\"referenceCaseIds\": [\"" + id + "\"]");

            BusinessException failure = rejected("RetainCaseRequest", body);

            assertThat(fields(failure)).as("reference UUID %s", id).containsExactly("referenceCaseIds[0]");
            assertThat(failure.fieldErrors())
                    .extracting(Problem::code)
                    .containsExactly("INVALID_FORMAT");
        }
    }

    @Test
    void calendarDatesEnumsCollectionBoundsAndDuplicatesComeFromTheContract() {
        BusinessException invalidDate = rejected(
                "RetainCaseRequest",
                replace(RETAIN_CASE, "\"receivedDate\": null", "\"receivedDate\": \"2024-02-30\""));
        assertThat(fields(invalidDate)).containsExactly("receivedDate");

        for (String expanded : List.of("+10000-01-01", "-0001-01-01", "10000-01-01")) {
            BusinessException failure = rejected(
                    "RetainCaseRequest",
                    replace(RETAIN_CASE, "\"receivedDate\": null", "\"receivedDate\": \"" + expanded + "\""));
            assertThat(fields(failure)).as("date %s", expanded).containsExactly("receivedDate");
            assertThat(failure.fieldErrors())
                    .extracting(Problem::code)
                    .containsExactly("INVALID_FORMAT");
        }

        BusinessException invalidEnum = rejected(
                "RetainCaseRequest",
                replace(RETAIN_CASE, "\"outcomeAssessment\": \"UNKNOWN\"", "\"outcomeAssessment\": \"unknown\""));
        assertThat(fields(invalidEnum)).containsExactly("outcomeAssessment");

        String tooManyReferences = String.join(
                ",",
                java.util.stream.IntStream.range(0, 21)
                        .mapToObj(index -> "\"" + java.util.UUID.randomUUID() + "\"")
                        .toList());
        BusinessException tooMany = rejected(
                "RetainCaseRequest",
                replace(
                        RETAIN_CASE,
                        "\"referenceCaseIds\": []",
                        "\"referenceCaseIds\": [" + tooManyReferences + "]"));
        assertThat(fields(tooMany)).containsExactly("referenceCaseIds");
        assertThat(tooMany.fieldErrors())
                .extracting(Problem::code)
                .containsExactly("OUT_OF_RANGE");

        String duplicate = java.util.UUID.randomUUID().toString();
        BusinessException duplicated = rejected(
                "RetainCaseRequest",
                replace(
                        RETAIN_CASE,
                        "\"referenceCaseIds\": []",
                        "\"referenceCaseIds\": [\"" + duplicate + "\", \"" + duplicate + "\"]"));
        assertThat(fields(duplicated)).containsExactly("referenceCaseIds[1]");
        assertThat(duplicated.fieldErrors())
                .extracting(Problem::code)
                .containsExactly("DUPLICATE");
    }

    @Test
    void nulAndLoneSurrogatesAreRejectedBeforeBinding() {
        String nul = "\\" + "u0000";
        String high = "\\" + "ud800";
        String low = "\\" + "udfff";
        for (String[] scenario : List.of(
                new String[] {
                    "narrative",
                    "\"narrative\": \"My transfer is missing from my checking account.\"",
                    "\"narrative\": \"My transfer is missing" + nul + " from my account.\""},
                new String[] {
                    "narrative",
                    "\"narrative\": \"My transfer is missing from my checking account.\"",
                    "\"narrative\": \"My transfer is missing" + high + " from my account.\""},
                new String[] {
                    "product",
                    "\"product\": \"Checking or savings account\"",
                    "\"product\": \"" + nul + "Checking or savings account\""},
                new String[] {
                    "issue",
                    "\"issue\": \"Managing an account\"",
                    "\"issue\": \"Managing" + low + " an account\""},
                new String[] {
                    "company",
                    "\"company\": null",
                    "\"company\": \"Company" + nul + "\""},
                new String[] {
                    "companyResponse",
                    "\"companyResponse\": null",
                    "\"companyResponse\": \"Closed" + high + "\""},
                new String[] {
                    "handlingNotes",
                    "\"handlingNotes\": \"Reviewed transfer evidence and requested a trace.\"",
                    "\"handlingNotes\": \"Notes" + nul + "\""},
                new String[] {
                    "evaluationNotes",
                    "\"evaluationNotes\": null",
                    "\"evaluationNotes\": \"Eval" + low + "\""})) {
            BusinessException failure = rejected(
                    "RetainCaseRequest",
                    replace(RETAIN_CASE, scenario[1], scenario[2]));
            assertThat(fields(failure)).as(scenario[0]).containsExactly(scenario[0]);
            assertThat(failure.fieldErrors())
                    .extracting(Problem::code)
                    .containsExactly("INVALID_FORMAT");
        }
    }

    @Test
    void uuidDuplicatesUseUuidIdentityAndCaseVariantsAreRejected() {
        String id = java.util.UUID.randomUUID().toString();
        for (String[] pair : List.of(
                new String[] {id, id},
                new String[] {id, id.toUpperCase()})) {
            BusinessException failure = rejected(
                    "RetainCaseRequest",
                    replace(
                            RETAIN_CASE,
                            "\"referenceCaseIds\": []",
                            "\"referenceCaseIds\": [\"" + pair[0] + "\", \"" + pair[1] + "\"]"));
            assertThat(fields(failure)).containsExactly("referenceCaseIds[1]");
            assertThat(failure.fieldErrors())
                    .extracting(Problem::code)
                    .containsExactly("DUPLICATE");
        }

        RULES.validateJson(
                "RetainCaseRequest",
                replace(
                        RETAIN_CASE,
                        "\"referenceCaseIds\": []",
                        "\"referenceCaseIds\": [\"" + id.toUpperCase() + "\"]"));
    }

    @Test
    void integerValuedDecimalsAreAcceptedButFractionsAreRejected() {
        for (String accepted : List.of("1.0", "1.00", "1e0", "2E1")) {
            RULES.validateJson("SearchRequest", replace(SEARCH, "\"limit\": 5", "\"limit\": " + accepted));
        }

        for (String rejected : List.of("1.5", "0.999", "20.0001")) {
            BusinessException failure = rejected(
                    "SearchRequest", replace(SEARCH, "\"limit\": 5", "\"limit\": " + rejected));
            assertThat(fields(failure)).as("limit %s", rejected).containsExactly("limit");
        }
    }

    @Test
    void malformedOrNonObjectJsonIsAMalformedRequestWithoutFieldErrors() {
        for (String body : List.of("", "{\"product\":", "[]", "\"text\"")) {
            BusinessException failure = rejected("RetainCaseRequest", body);

            assertThat(HttpStatusOf.of(failure)).as("body %s", body).isEqualTo(400);
            assertThat(failure.code()).isEqualTo("MALFORMED_REQUEST");
            assertThat(failure.fieldErrors()).isEmpty();
        }
    }

    private static BusinessException rejected(String schema, String body) {
        BusinessException failure = catchThrowableOfType(
                () -> RULES.validateJson(schema, body), BusinessException.class);
        assertThat(failure).as("expected a BusinessException for body %s", body).isNotNull();
        return failure;
    }

    private static List<String> fields(BusinessException failure) {
        return failure.fieldErrors().stream().map(Problem::field).toList();
    }

    private static String replace(String body, String target, String replacement) {
        assertThat(body).contains(target);
        return body.replace(target, replacement);
    }
}
