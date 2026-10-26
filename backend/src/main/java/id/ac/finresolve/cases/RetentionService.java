package id.ac.finresolve.cases;

import id.ac.finresolve.categories.CategoryService;
import id.ac.finresolve.categories.CategoryValues;
import id.ac.finresolve.db.Database;
import id.ac.finresolve.http.BusinessException;
import id.ac.finresolve.http.BusinessException.Problem;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Creates and fully replaces ANALYST cases in one serialized write transaction.
 *
 * <p>Shape validation runs first. Catalog membership, target source/existence, references, and
 * capacity are then checked inside the same transaction as the case, notes, and reference writes.
 * A failure therefore leaves the previous case untouched, and a full PUT never leaves old notes or
 * references behind.
 */
@Service
public final class RetentionService {

    private static final int MAX_REFERENCES = 20;
    private static final int MAX_NOTES_CODE_POINTS = 10_000;

    private final CaseRepository cases;
    private final CategoryService categories;
    private final Database database;
    private final Clock clock;
    private final Supplier<UUID> idSupplier;

    @Autowired
    public RetentionService(
            CaseRepository cases, CategoryService categories, Database database) {
        this(cases, categories, database, Clock.systemUTC(), UUID::randomUUID);
    }

    RetentionService(
            CaseRepository cases,
            CategoryService categories,
            Database database,
            Clock clock,
            Supplier<UUID> idSupplier) {
        this.cases = Objects.requireNonNull(cases, "cases");
        this.categories = Objects.requireNonNull(categories, "categories");
        this.database = Objects.requireNonNull(database, "database");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.idSupplier = Objects.requireNonNull(idSupplier, "idSupplier");
    }

    public CaseRecord create(RetainCommand command) {
        Validated validated = validate(command);
        return database.write(() -> {
            CategoryValues canonical = categories.canonicalize(
                    validated.complaint().categories());
            UUID id = idSupplier.get();
            if (validated.referenceCaseIds().contains(id)) {
                throw invalidReference(List.of(new Problem(
                        "referenceCaseIds",
                        "INVALID_VALUE",
                        "Kasus tidak boleh mereferensikan dirinya sendiri.")));
            }
            validateReferencesExist(validated.referenceCaseIds());
            if (cases.count() >= CaseRepository.MAX_CASES) {
                throw new BusinessException(
                        409,
                        "KNOWLEDGE_BASE_LIMIT_EXCEEDED",
                        "Basis kasus mencapai batas 5.000; retensi tidak dapat disimpan.",
                        List.of());
            }

            Instant now = clock.instant();
            CaseRecord record = new CaseRecord(
                    id,
                    CaseRecord.Source.ANALYST,
                    canonical,
                    validated.complaint().narrative(),
                    validated.complaint().receivedDate(),
                    validated.complaint().company(),
                    validated.complaint().companyResponse(),
                    now,
                    now,
                    null,
                    validated.notes(),
                    validated.referenceCaseIds());
            cases.insert(record);
            return record;
        });
    }

    public CaseRecord replace(UUID id, RetainCommand command) {
        Objects.requireNonNull(id, "id");
        Validated validated = validate(command);
        return database.write(() -> {
            CaseRecord existing = cases.find(id).orElseThrow(() -> new BusinessException(
                    404, "CASE_NOT_FOUND", "Kasus tidak ditemukan.", List.of()));
            if (existing.source() != CaseRecord.Source.ANALYST) {
                throw new BusinessException(
                        409,
                        "IMMUTABLE_CFPB_CASE",
                        "Kasus arsip CFPB tidak dapat diubah.",
                        List.of());
            }
            if (validated.referenceCaseIds().contains(id)) {
                throw invalidReference(List.of(new Problem(
                        "referenceCaseIds",
                        "INVALID_VALUE",
                        "Kasus tidak boleh mereferensikan dirinya sendiri.")));
            }
            validateReferencesExist(validated.referenceCaseIds());
            CategoryValues canonical = categories.canonicalize(
                    validated.complaint().categories());

            CaseRecord replacement = new CaseRecord(
                    existing.id(),
                    CaseRecord.Source.ANALYST,
                    canonical,
                    validated.complaint().narrative(),
                    validated.complaint().receivedDate(),
                    validated.complaint().company(),
                    validated.complaint().companyResponse(),
                    existing.createdAt(),
                    clock.instant(),
                    null,
                    validated.notes(),
                    validated.referenceCaseIds());
            cases.replaceAnalyst(replacement);
            return cases.find(id).orElseThrow(() -> new IllegalStateException(
                    "ANALYST case disappeared after replacement."));
        });
    }

    private Validated validate(RetainCommand command) {
        if (command == null) {
            throw new BusinessException(
                    400,
                    "VALIDATION_ERROR",
                    "Input retensi tidak valid.",
                    List.of(new Problem("body", "REQUIRED", "Badan permintaan wajib diisi.")));
        }

        ComplaintInput complaint = InputRules.complaint(command.complaint());
        List<Problem> problems = new ArrayList<>();

        CaseRecord.Notes requestedNotes = command.notes();
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
            throw new BusinessException(
                    400, "VALIDATION_ERROR", "Input retensi tidak valid.", problems);
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

    private void validateReferencesExist(List<UUID> references) {
        List<Problem> problems = new ArrayList<>();
        for (int index = 0; index < references.size(); index++) {
            UUID reference = references.get(index);
            if (reference != null && cases.find(reference).isEmpty()) {
                problems.add(new Problem(
                        "referenceCaseIds[" + index + "]",
                        "NOT_FOUND",
                        "Kasus referensi tidak ditemukan."));
            }
        }
        if (!problems.isEmpty()) {
            throw invalidReference(problems);
        }
    }

    private static BusinessException invalidReference(List<Problem> problems) {
        return new BusinessException(
                400,
                "INVALID_REFERENCE",
                "Referensi kasus tidak valid.",
                problems);
    }

    private static int codePointLength(String value) {
        return value.codePointCount(0, value.length());
    }

    private static String trim(String value) {
        return value == null ? null : value.strip();
    }

    private record Validated(
            ComplaintInput complaint,
            CaseRecord.Notes notes,
            List<UUID> referenceCaseIds) {
    }
}
