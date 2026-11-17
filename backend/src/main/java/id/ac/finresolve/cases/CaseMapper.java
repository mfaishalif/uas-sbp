package id.ac.finresolve.cases;

import id.ac.finresolve.api.model.AnalystCase;
import id.ac.finresolve.api.model.AnalystNotes;
import id.ac.finresolve.api.model.ArchiveProvenance;
import id.ac.finresolve.api.model.CaseDetail;
import id.ac.finresolve.api.model.CasePage;
import id.ac.finresolve.api.model.CaseSummary;
import id.ac.finresolve.api.model.CfpbCase;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Maps case records to the generated transport models without leaking domain types. */
@Component
public final class CaseMapper {

    public CaseDetail caseDetail(CaseRecord record) {
        return switch (record.source()) {
            case CFPB -> cfpbCase(record);
            case ANALYST -> analystCase(record);
        };
    }

    public CasePage casePage(id.ac.finresolve.cases.CasePage page) {
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
        CaseRecord.Provenance provenance = Objects.requireNonNull(
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
        CaseRecord.Notes notes = Objects.requireNonNull(
                record.notes(), "ANALYST notes");
        AnalystNotes mappedNotes = new AnalystNotes(
                notes.handlingNotes(),
                AnalystNotes.OutcomeAssessmentEnum.fromValue(notes.outcomeAssessment().name()),
                notes.evaluationNotes());

        Set<UUID> references = new LinkedHashSet<>(record.referenceCaseIds());
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
