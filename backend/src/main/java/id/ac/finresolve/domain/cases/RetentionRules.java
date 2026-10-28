package id.ac.finresolve.domain.cases;

import id.ac.finresolve.domain.error.BusinessException;
import id.ac.finresolve.domain.error.BusinessException.Problem;
import id.ac.finresolve.domain.text.UnicodeText;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Shape validation for a complete analyst retention command. */
public final class RetentionRules {

    private static final int MAX_REFERENCES = 20;
    private static final int MAX_NOTES_CODE_POINTS = 10_000;

    private RetentionRules() {
    }

    public static Validated validate(RetainCommand command) {
        if (command == null) {
            throw new BusinessException(
                    "VALIDATION_ERROR",
                    "Input retensi tidak valid.",
                    List.of(new Problem("body", "REQUIRED", "Badan permintaan wajib diisi.")));
        }

        ComplaintInput complaint = InputRules.complaint(command.complaint());
        List<Problem> problems = new ArrayList<>();

        CaseRecord.Notes requestedNotes = command.notes();
        UnicodeText.addProblemIfInvalid(
                requestedNotes == null ? null : requestedNotes.handlingNotes(),
                "handlingNotes",
                problems);
        UnicodeText.addProblemIfInvalid(
                requestedNotes == null ? null : requestedNotes.evaluationNotes(),
                "evaluationNotes",
                problems);
        String handlingNotes = trim(requestedNotes == null ? null : requestedNotes.handlingNotes());
        if (handlingNotes == null || handlingNotes.isEmpty()) {
            problems.add(new Problem(
                    "handlingNotes", "REQUIRED", "Catatan penanganan wajib diisi."));
        } else if (codePointLength(handlingNotes) > MAX_NOTES_CODE_POINTS) {
            problems.add(new Problem(
                    "handlingNotes",
                    "TOO_LONG",
                    "Catatan penanganan maksimal 10.000 karakter."));
        }

        String evaluationNotes = trim(
                requestedNotes == null ? null : requestedNotes.evaluationNotes());
        if (evaluationNotes != null && evaluationNotes.isEmpty()) {
            evaluationNotes = null;
        }
        if (evaluationNotes != null && codePointLength(evaluationNotes) > MAX_NOTES_CODE_POINTS) {
            problems.add(new Problem(
                    "evaluationNotes",
                    "TOO_LONG",
                    "Catatan evaluasi maksimal 10.000 karakter."));
        }

        CaseRecord.Outcome outcome = requestedNotes == null
                ? null
                : requestedNotes.outcomeAssessment();
        if (outcome == null) {
            problems.add(new Problem(
                    "outcomeAssessment", "REQUIRED", "Penilaian hasil wajib diisi."));
        }

        List<UUID> references = command.referenceCaseIds();
        if (!problems.isEmpty()) {
            throw new BusinessException("VALIDATION_ERROR", "Input retensi tidak valid.", problems);
        }

        List<Problem> referenceProblems = new ArrayList<>();
        validateReferenceShape(references, referenceProblems);
        if (!referenceProblems.isEmpty()) {
            throw invalidReference(referenceProblems);
        }

        return new Validated(
                complaint,
                new CaseRecord.Notes(handlingNotes, outcome, evaluationNotes),
                references);
    }

    public static BusinessException invalidReference(List<Problem> problems) {
        return new BusinessException(
                "INVALID_REFERENCE",
                "Referensi kasus tidak valid.",
                problems);
    }

    private static void validateReferenceShape(List<UUID> references, List<Problem> problems) {
        if (references.size() > MAX_REFERENCES) {
            problems.add(new Problem(
                    "referenceCaseIds",
                    "OUT_OF_RANGE",
                    "Jumlah referensi maksimal 20."));
        }
        Set<UUID> seen = new HashSet<>();
        for (int index = 0; index < references.size(); index++) {
            UUID reference = references.get(index);
            if (reference == null) {
                problems.add(new Problem(
                        "referenceCaseIds[" + index + "]",
                        "INVALID_VALUE",
                        "Referensi tidak boleh null."));
            } else if (!seen.add(reference)) {
                problems.add(new Problem(
                        "referenceCaseIds[" + index + "]",
                        "DUPLICATE",
                        "Referensi tidak boleh duplikat."));
            }
        }
    }

    private static int codePointLength(String value) {
        return value.codePointCount(0, value.length());
    }

    private static String trim(String value) {
        return value == null ? null : value.strip();
    }

    public record Validated(
            ComplaintInput complaint,
            CaseRecord.Notes notes,
            List<UUID> referenceCaseIds) {

        public Validated {
            referenceCaseIds = List.copyOf(referenceCaseIds);
        }
    }
}
