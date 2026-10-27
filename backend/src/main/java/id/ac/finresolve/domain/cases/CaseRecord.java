package id.ac.finresolve.domain.cases;

import id.ac.finresolve.domain.category.CategoryValues;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Internal case representation shared by imports, reads, retention, and search.
 *
 * <p>CFPB cases carry provenance and no analyst notes or references. ANALYST cases carry notes
 * (required by the repository invariant) and optional references, but never provenance.
 */
public record CaseRecord(
        UUID id,
        Source source,
        CategoryValues categories,
        String narrative,
        LocalDate receivedDate,
        String company,
        String companyResponse,
        Instant createdAt,
        Instant updatedAt,
        Provenance provenance,
        Notes notes,
        List<UUID> referenceCaseIds) {

    public CaseRecord {
        referenceCaseIds = referenceCaseIds == null ? List.of() : List.copyOf(referenceCaseIds);
    }

    public enum Source {
        CFPB,
        ANALYST
    }

    public enum Outcome {
        UNKNOWN,
        RESOLVED,
        PARTIALLY_RESOLVED,
        UNRESOLVED
    }

    public record Provenance(
            UUID batchId,
            String complaintId,
            String archiveUrl,
            String sourceFileName,
            LocalDate periodStart,
            LocalDate periodEnd,
            String csvSha256) {
    }

    public record Notes(String handlingNotes, Outcome outcomeAssessment, String evaluationNotes) {
    }
}
