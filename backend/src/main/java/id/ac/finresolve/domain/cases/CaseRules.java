package id.ac.finresolve.domain.cases;

import java.math.BigInteger;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Source-specific invariants for a materialized case before persistence. */
public final class CaseRules {

    private CaseRules() {
    }

    public static void validate(CaseRecord record) {
        Objects.requireNonNull(record, "record");
        Objects.requireNonNull(record.id(), "id");
        Objects.requireNonNull(record.source(), "source");
        Objects.requireNonNull(record.categories(), "categories");
        requireText(record.categories().product(), "product");
        requireText(record.categories().issue(), "issue");
        requireText(record.narrative(), "narrative");
        Objects.requireNonNull(record.createdAt(), "createdAt");
        Objects.requireNonNull(record.updatedAt(), "updatedAt");
        if (record.updatedAt().isBefore(record.createdAt())) {
            throw new IllegalArgumentException("updatedAt must not be before createdAt.");
        }

        List<UUID> references = record.referenceCaseIds();
        if (references.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("referenceCaseIds must not contain nulls.");
        }
        if (new HashSet<>(references).size() != references.size()) {
            throw new IllegalArgumentException("referenceCaseIds must be distinct.");
        }
        if (references.contains(record.id())) {
            throw new IllegalArgumentException("A case cannot reference itself.");
        }

        switch (record.source()) {
            case CFPB -> validateCfpb(record);
            case ANALYST -> validateAnalyst(record);
        }
    }

    private static void validateCfpb(CaseRecord record) {
        if (record.notes() != null) {
            throw new IllegalArgumentException("CFPB cases must not have analyst notes.");
        }
        if (!record.referenceCaseIds().isEmpty()) {
            throw new IllegalArgumentException("CFPB cases must not have reference cases.");
        }
        if (record.provenance() == null) {
            throw new IllegalArgumentException("CFPB cases require provenance.");
        }
        CaseRecord.Provenance provenance = record.provenance();
        requireNonNull(provenance.batchId(), "provenance.batchId");
        requireText(provenance.complaintId(), "provenance.complaintId");
        requireText(provenance.archiveUrl(), "provenance.archiveUrl");
        requireText(provenance.sourceFileName(), "provenance.sourceFileName");
        requireNonNull(provenance.periodStart(), "provenance.periodStart");
        requireNonNull(provenance.periodEnd(), "provenance.periodEnd");
        requireText(provenance.csvSha256(), "provenance.csvSha256");
        if (!provenance.complaintId().matches("[1-9][0-9]*")
                || new BigInteger(provenance.complaintId()).signum() <= 0) {
            throw new IllegalArgumentException(
                    "CFPB complaintId must be a canonical positive decimal integer.");
        }
    }

    private static void validateAnalyst(CaseRecord record) {
        if (record.provenance() != null) {
            throw new IllegalArgumentException("ANALYST cases must not have provenance.");
        }
        if (record.notes() == null) {
            throw new IllegalArgumentException("ANALYST cases require analyst notes.");
        }
        requireText(record.notes().handlingNotes(), "analyst handling notes");
        requireNonNull(record.notes().outcomeAssessment(), "notes.outcomeAssessment");
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required.");
        }
    }

    private static void requireNonNull(Object value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + " is required.");
        }
    }
}
