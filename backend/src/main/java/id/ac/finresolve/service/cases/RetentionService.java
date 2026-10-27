package id.ac.finresolve.service.cases;

import id.ac.finresolve.domain.cases.CaseCapacity;
import id.ac.finresolve.domain.cases.CaseRecord;
import id.ac.finresolve.domain.cases.RetainCommand;
import id.ac.finresolve.domain.cases.RetentionRules;
import id.ac.finresolve.domain.category.CategoryValues;
import id.ac.finresolve.domain.error.BusinessException;
import id.ac.finresolve.domain.error.BusinessException.Problem;
import id.ac.finresolve.repository.TransactionOperations;
import id.ac.finresolve.repository.cases.CaseRepository;
import id.ac.finresolve.service.category.CategoryService;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
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

    private final CaseRepository cases;
    private final CategoryService categories;
    private final TransactionOperations transactions;
    private final Clock clock;
    private final Supplier<UUID> idSupplier;

    @Autowired
    public RetentionService(
            CaseRepository cases,
            CategoryService categories,
            TransactionOperations transactions) {
        this(cases, categories, transactions, Clock.systemUTC(), UUID::randomUUID);
    }

    RetentionService(
            CaseRepository cases,
            CategoryService categories,
            TransactionOperations transactions,
            Clock clock,
            Supplier<UUID> idSupplier) {
        this.cases = Objects.requireNonNull(cases, "cases");
        this.categories = Objects.requireNonNull(categories, "categories");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.idSupplier = Objects.requireNonNull(idSupplier, "idSupplier");
    }

    public CaseRecord create(RetainCommand command) {
        RetentionRules.Validated validated = RetentionRules.validate(command);
        return transactions.write(() -> {
            CategoryValues canonical = categories.canonicalize(
                    validated.complaint().categories());
            UUID id = idSupplier.get();
            if (validated.referenceCaseIds().contains(id)) {
                throw RetentionRules.invalidReference(List.of(new Problem(
                        "referenceCaseIds",
                        "INVALID_VALUE",
                        "Kasus tidak boleh mereferensikan dirinya sendiri.")));
            }
            validateReferencesExist(validated.referenceCaseIds());
            if (CaseCapacity.wouldExceed(cases.count(), 1)) {
                throw new BusinessException("KNOWLEDGE_BASE_LIMIT_EXCEEDED",
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
        RetentionRules.Validated validated = RetentionRules.validate(command);
        return transactions.write(() -> {
            CaseRecord existing = cases.find(id).orElseThrow(() -> new BusinessException(
                    "CASE_NOT_FOUND", "Kasus tidak ditemukan.", List.of()));
            if (existing.source() != CaseRecord.Source.ANALYST) {
                throw new BusinessException("IMMUTABLE_CFPB_CASE",
                        "Kasus arsip CFPB tidak dapat diubah.",
                        List.of());
            }
            if (validated.referenceCaseIds().contains(id)) {
                throw RetentionRules.invalidReference(List.of(new Problem(
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
            throw RetentionRules.invalidReference(problems);
        }
    }
}
