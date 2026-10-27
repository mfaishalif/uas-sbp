package id.ac.finresolve.domain.cases;

import java.util.List;

/** One materialized page of cases, preserving the requested pagination metadata. */
public record CasePage(List<CaseRecord> items, int page, int size, long totalElements) {

    public CasePage {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
