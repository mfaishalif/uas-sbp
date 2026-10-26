package id.ac.finresolve.cases;

import id.ac.finresolve.api.CasesApi;
import id.ac.finresolve.api.model.CaseDetail;
import id.ac.finresolve.api.model.CasePage;
import id.ac.finresolve.api.model.RetainCaseRequest;
import id.ac.finresolve.http.ApiMapper;
import id.ac.finresolve.http.BusinessException;
import id.ac.finresolve.http.BusinessException.Problem;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Contract-generated case reads plus explicit temporary mutation placeholders. */
@RestController
@RequestMapping("/api")
public class CasesController implements CasesApi {

    private final CaseReadService reads;
    private final ApiMapper mapper;

    public CasesController(CaseReadService reads, ApiMapper mapper) {
        this.reads = reads;
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
        throw unavailable();
    }

    @Override
    public ResponseEntity<CaseDetail> replaceAnalystCase(
            UUID id, RetainCaseRequest retainCaseRequest) {
        throw unavailable();
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
        return new BusinessException(
                400,
                "VALIDATION_ERROR",
                "Parameter permintaan tidak valid.",
                List.of(new Problem(field, code, message)));
    }

    private static BusinessException unavailable() {
        return new BusinessException(
                503,
                "SERVICE_UNAVAILABLE",
                "Endpoint retensi belum tersedia.",
                List.of());
    }
}
