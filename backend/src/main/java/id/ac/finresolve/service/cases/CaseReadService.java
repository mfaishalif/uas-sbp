package id.ac.finresolve.service.cases;

import id.ac.finresolve.domain.cases.CasePage;
import id.ac.finresolve.domain.cases.CaseRecord;
import id.ac.finresolve.domain.error.BusinessException;
import id.ac.finresolve.repository.cases.CaseRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Read-only case access used by the HTTP boundary. */
@Service
public final class CaseReadService {

    private final CaseRepository repository;

    public CaseReadService(CaseRepository repository) {
        this.repository = repository;
    }

    public CaseRecord get(UUID id) {
        return repository.find(id).orElseThrow(() -> new BusinessException(
                "CASE_NOT_FOUND",
                "Kasus tidak ditemukan.",
                List.of()));
    }

    public CasePage list(int page, int size, CaseRecord.Source source) {
        return repository.page(page, size, source);
    }
}
