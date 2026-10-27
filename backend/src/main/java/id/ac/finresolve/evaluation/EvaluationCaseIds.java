package id.ac.finresolve.evaluation;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/**
 * Stable evaluation identity derived from the external CFPB complaint ID.
 *
 * <p>Production imports generate random internal UUIDs, so an offline series cannot be reproduced
 * from those values. Evaluation cases use a name-based UUID instead; the CFPB complaint ID remains
 * the identity used by exported candidates and judgments.
 */
public final class EvaluationCaseIds {

    private static final String NAMESPACE_PREFIX = "finresolve-evaluation-cfpb:";

    private EvaluationCaseIds() {
    }

    public static UUID fromComplaintId(String complaintId) {
        Objects.requireNonNull(complaintId, "complaintId");
        String trimmed = complaintId.strip();
        if (!trimmed.matches("[1-9][0-9]*")) {
            throw new IllegalArgumentException(
                    "CFPB complaint ID must be a positive integer: " + complaintId);
        }
        return UUID.nameUUIDFromBytes(
                (NAMESPACE_PREFIX + trimmed).getBytes(StandardCharsets.UTF_8));
    }

    public static String complaintId(id.ac.finresolve.domain.cases.CaseRecord record) {
        Objects.requireNonNull(record, "record");
        if (record.provenance() == null || record.provenance().complaintId() == null) {
            throw new IllegalArgumentException(
                    "Evaluation case has no CFPB complaint ID: " + record.id());
        }
        return record.provenance().complaintId();
    }
}
