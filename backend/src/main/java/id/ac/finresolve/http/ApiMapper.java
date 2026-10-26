package id.ac.finresolve.http;

import id.ac.finresolve.api.model.AnalystCase;
import id.ac.finresolve.api.model.AnalystNotes;
import id.ac.finresolve.api.model.ApiError;
import id.ac.finresolve.api.model.ArchiveProvenance;
import id.ac.finresolve.api.model.CaseDetail;
import id.ac.finresolve.api.model.CasePage;
import id.ac.finresolve.api.model.CaseSummary;
import id.ac.finresolve.api.model.Categories;
import id.ac.finresolve.api.model.CategoryTuple;
import id.ac.finresolve.api.model.CfpbCase;
import id.ac.finresolve.api.model.FieldError;
import id.ac.finresolve.api.model.Health;
import id.ac.finresolve.api.model.ImportMetadata;
import id.ac.finresolve.api.model.ImportReport;
import id.ac.finresolve.api.model.ImportRowError;
import id.ac.finresolve.cases.CaseRecord;
import id.ac.finresolve.cases.CaseRepository;
import id.ac.finresolve.categories.CategoryValues;
import id.ac.finresolve.imports.BatchRecord;
import id.ac.finresolve.imports.ImportRowProblem;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Maps internal records to the generated transport models without leaking domain types. */
@Component
public final class ApiMapper {

    public Health health() {
        return new Health(Health.StatusEnum.UP, Health.DatabaseEnum.UP);
    }

    public CaseDetail caseDetail(CaseRecord record) {
        return switch (record.source()) {
            case CFPB -> cfpbCase(record);
            case ANALYST -> analystCase(record);
        };
    }

    public CasePage casePage(CaseRepository.Page page) {
        List<CaseSummary> items = new ArrayList<>(page.items().size());
        for (CaseRecord record : page.items()) {
            items.add(caseSummary(record));
        }

        long total = page.totalElements();
        long size = page.size();
        long totalPages = total == 0 ? 0 : (total + size - 1) / size;
        boolean hasNext = (long) page.page() + 1 < totalPages;
        return new CasePage(
                items,
                page.page(),
                page.size(),
                Math.toIntExact(total),
                Math.toIntExact(totalPages),
                hasNext);
    }

    public Categories categories(List<CategoryValues> values) {
        List<CategoryTuple> items = new ArrayList<>(values.size());
        for (CategoryValues value : values) {
            items.add(new CategoryTuple(
                    value.product(), value.subProduct(), value.issue(), value.subIssue()));
        }
        return new Categories(items, Categories.ScopeEnum.IMPORTED_CFPB_SAMPLE);
    }

    public ImportReport importReport(BatchRecord batch, boolean replayed) {
        ImportMetadata metadata = new ImportMetadata(
                batch.archiveUrl(),
                batch.sourceFileName(),
                batch.periodStart(),
                batch.periodEnd());
        List<ImportRowError> errors = new ArrayList<>(batch.errors().size());
        for (ImportRowProblem problem : batch.errors()) {
            errors.add(new ImportRowError(
                    problem.recordNumber(),
                    problem.field(),
                    ImportRowError.CodeEnum.fromValue(problem.code()),
                    problem.message()));
        }
        return new ImportReport(
                batch.id(),
                replayed,
                metadata,
                batch.csvSha256(),
                batch.processed(),
                batch.imported(),
                batch.duplicates(),
                batch.rejected(),
                errors,
                batch.rejected() > errors.size(),
                offset(batch.importedAt()));
    }

    public ApiError error(BusinessException failure, Instant timestamp) {
        List<FieldError> fieldErrors = new ArrayList<>(failure.fieldErrors().size());
        for (BusinessException.Problem problem : failure.fieldErrors()) {
            fieldErrors.add(new FieldError(
                    problem.field(),
                    FieldError.CodeEnum.fromValue(problem.code()),
                    problem.message()));
        }
        return new ApiError(
                ApiError.CodeEnum.fromValue(failure.code()),
                failure.getMessage(),
                fieldErrors,
                OffsetDateTime.ofInstant(timestamp, ZoneOffset.UTC));
    }

    public ApiError globalError(String code, String message, Instant timestamp) {
        return new ApiError(
                ApiError.CodeEnum.fromValue(code),
                message,
                List.of(),
                OffsetDateTime.ofInstant(timestamp, ZoneOffset.UTC));
    }

    private CaseSummary caseSummary(CaseRecord record) {
        return new CaseSummary(
                record.id(),
                CaseSummary.SourceEnum.fromValue(record.source().name()),
                record.categories().product(),
                record.categories().subProduct(),
                record.categories().issue(),
                record.categories().subIssue(),
                record.receivedDate(),
                record.company(),
                record.companyResponse(),
                offset(record.createdAt()),
                offset(record.updatedAt()));
    }

    private CfpbCase cfpbCase(CaseRecord record) {
        CaseRecord.Provenance provenance = java.util.Objects.requireNonNull(
                record.provenance(), "CFPB provenance");
        ArchiveProvenance mappedProvenance = new ArchiveProvenance(
                provenance.complaintId(),
                provenance.batchId(),
                provenance.archiveUrl(),
                provenance.sourceFileName(),
                provenance.periodStart(),
                provenance.periodEnd(),
                provenance.csvSha256());

        return new CfpbCase(
                record.id(),
                CfpbCase.SourceEnum.CFPB,
                record.categories().product(),
                record.categories().subProduct(),
                record.categories().issue(),
                record.categories().subIssue(),
                record.receivedDate(),
                record.company(),
                record.companyResponse(),
                offset(record.createdAt()),
                offset(record.updatedAt()),
                record.narrative(),
                mappedProvenance,
                null,
                List.of());
    }

    private AnalystCase analystCase(CaseRecord record) {
        CaseRecord.Notes notes = java.util.Objects.requireNonNull(
                record.notes(), "ANALYST notes");
        AnalystNotes mappedNotes = new AnalystNotes(
                notes.handlingNotes(),
                AnalystNotes.OutcomeAssessmentEnum.fromValue(notes.outcomeAssessment().name()),
                notes.evaluationNotes());

        Set<java.util.UUID> references = new LinkedHashSet<>(record.referenceCaseIds());
        return new AnalystCase(
                record.id(),
                AnalystCase.SourceEnum.ANALYST,
                record.categories().product(),
                record.categories().subProduct(),
                record.categories().issue(),
                record.categories().subIssue(),
                record.receivedDate(),
                record.company(),
                record.companyResponse(),
                offset(record.createdAt()),
                offset(record.updatedAt()),
                record.narrative(),
                null,
                mappedNotes,
                references);
    }

    private static OffsetDateTime offset(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
