package id.ac.finresolve.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.ac.finresolve.cases.CaseRecord;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EvaluationCaseIdsTest {

    @Test
    void derivesTheSameUuidFromTheSameCfpbComplaintId() {
        UUID first = EvaluationCaseIds.fromComplaintId("10648930");
        UUID second = EvaluationCaseIds.fromComplaintId(" 10648930 ");

        assertThat(first).isEqualTo(second);
        assertThat(first).isEqualTo(
                UUID.fromString("521ebee7-246c-312f-8cfd-971c026af345"));
        assertThat(first.version()).isEqualTo(3);
    }

    @Test
    void derivesDifferentUuidsForDifferentComplaintIds() {
        assertThat(EvaluationCaseIds.fromComplaintId("10648930"))
                .isNotEqualTo(EvaluationCaseIds.fromComplaintId("10648931"));
    }

    @Test
    void rejectsNonNumericComplaintIds() {
        assertThatThrownBy(() -> EvaluationCaseIds.fromComplaintId("CFPB-1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EvaluationCaseIds.fromComplaintId("0"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EvaluationCaseIds.fromComplaintId(""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void readsTheExternalIdFromEvaluationProvenance() {
        UUID internal = EvaluationCaseIds.fromComplaintId("10648930");
        CaseRecord record = new CaseRecord(
                internal,
                CaseRecord.Source.CFPB,
                null,
                "narrative",
                null,
                null,
                null,
                null,
                null,
                new CaseRecord.Provenance(
                        UUID.randomUUID(), "10648930", "https://example.test/a.zip",
                        "sample.csv", null, null, "checksum"),
                null,
                List.of());

        assertThat(EvaluationCaseIds.complaintId(record)).isEqualTo("10648930");
    }
}
