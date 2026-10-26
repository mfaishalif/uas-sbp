package id.ac.finresolve.cases;

import id.ac.finresolve.http.BusinessException;
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
                404,
                "CASE_NOT_FOUND",
                "Kasus tidak ditemukan.",
                List.of()));
    }

    public CaseRepository.Page list(int page, int size, CaseRecord.Source source) {
        return repository.page(page, size, source);
    }
}
