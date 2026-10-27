package id.ac.finresolve.presentation.cases;

import id.ac.finresolve.api.CasesApi;
import id.ac.finresolve.api.model.CaseDetail;
import id.ac.finresolve.api.model.CasePage;
import id.ac.finresolve.api.model.RetainCaseRequest;
import id.ac.finresolve.domain.cases.CaseRecord;
import id.ac.finresolve.domain.cases.ComplaintInput;
import id.ac.finresolve.domain.cases.RetainCommand;
import id.ac.finresolve.domain.category.CategoryValues;
import id.ac.finresolve.domain.error.BusinessException;
import id.ac.finresolve.domain.error.BusinessException.Problem;
import id.ac.finresolve.presentation.mapper.ApiMapper;
import id.ac.finresolve.service.cases.CaseReadService;
import id.ac.finresolve.service.cases.RetentionService;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.openapitools.jackson.nullable.JsonNullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Contract-generated case reads, retention, and full replacement. */
@RestController
@RequestMapping("/api")
public class CasesController implements CasesApi {

    private final CaseReadService reads;
    private final RetentionService retention;
    private final ApiMapper mapper;

    public CasesController(
            CaseReadService reads, RetentionService retention, ApiMapper mapper) {
        this.reads = reads;
        this.retention = retention;
        this.mapper = mapper;
    }

    @Override
    public ResponseEntity<CaseDetail> getCase(UUID id) {
        return ResponseEntity.ok(mapper.caseDetail(reads.get(id)));
    }

    @Override
    public ResponseEntity<CasePage> listCases(Integer page, Integer size, String source) {
        int requestedPage = validatePage(page);
        int requestedSize = validateSize(size);
        CaseRecord.Source requestedSource = validateSource(source);
        return ResponseEntity.ok(
                mapper.casePage(reads.list(requestedPage, requestedSize, requestedSource)));
    }

    @Override
    public ResponseEntity<CaseDetail> retainCase(RetainCaseRequest retainCaseRequest) {
        CaseRecord created = retention.create(command(retainCaseRequest));
        return ResponseEntity.created(URI.create("/api/cases/" + created.id()))
                .body(mapper.caseDetail(created));
    }

    @Override
    public ResponseEntity<CaseDetail> replaceAnalystCase(
            UUID id, RetainCaseRequest retainCaseRequest) {
        return ResponseEntity.ok(
                mapper.caseDetail(retention.replace(id, command(retainCaseRequest))));
    }

    private static RetainCommand command(RetainCaseRequest request) {
        ComplaintInput complaint = new ComplaintInput(
                new CategoryValues(
                        request.getProduct(),
                        nullable(request.getSubProduct()),
                        request.getIssue(),
                        nullable(request.getSubIssue())),
                request.getNarrative(),
                nullable(request.getReceivedDate()),
                nullable(request.getCompany()),
                nullable(request.getCompanyResponse()));
        CaseRecord.Notes notes = new CaseRecord.Notes(
                request.getHandlingNotes(),
                request.getOutcomeAssessment() == null
                        ? null
                        : CaseRecord.Outcome.valueOf(
                                request.getOutcomeAssessment().getValue()),
                nullable(request.getEvaluationNotes()));
        List<UUID> references = request.getReferenceCaseIds() == null
                ? List.of()
                : List.copyOf(request.getReferenceCaseIds());
        return new RetainCommand(complaint, notes, references);
    }

    private static <T> T nullable(JsonNullable<T> value) {
        return value == null || !value.isPresent() ? null : value.get();
    }

    private static int validatePage(Integer page) {
        if (page == null) {
            throw invalid("page", "INVALID_FORMAT", "Halaman harus bilangan bulat.");
        }
        if (page < 0) {
            throw invalid("page", "OUT_OF_RANGE", "Halaman tidak boleh negatif.");
        }
        return page;
    }

    private static int validateSize(Integer size) {
        if (size == null) {
            throw invalid("size", "INVALID_FORMAT", "Ukuran halaman harus bilangan bulat.");
        }
        if (size < 1 || size > 100) {
            throw invalid("size", "OUT_OF_RANGE", "Ukuran halaman harus 1 sampai 100.");
        }
        return size;
    }

    private static CaseRecord.Source validateSource(String source) {
        if (source == null) {
            return null;
        }
        String normalized = source.toUpperCase(Locale.ROOT);
        if (!normalized.equals(source)
                || !List.of("CFPB", "ANALYST").contains(normalized)) {
            throw invalid("source", "INVALID_VALUE", "Nilai source harus CFPB atau ANALYST.");
        }
        return CaseRecord.Source.valueOf(normalized);
    }

    private static BusinessException invalid(String field, String code, String message) {
        return new BusinessException("VALIDATION_ERROR",
                "Parameter permintaan tidak valid.",
                List.of(new Problem(field, code, message)));
    }
}
