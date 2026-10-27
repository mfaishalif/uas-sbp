package id.ac.finresolve.presentation.search;

import id.ac.finresolve.api.SearchApi;
import id.ac.finresolve.api.model.SearchRequest;
import id.ac.finresolve.api.model.SearchResponse;
import id.ac.finresolve.domain.cases.ComplaintInput;
import id.ac.finresolve.domain.category.CategoryValues;
import id.ac.finresolve.presentation.mapper.ApiMapper;
import id.ac.finresolve.presentation.validation.RequestRules;
import id.ac.finresolve.service.search.SearchOutcome;
import id.ac.finresolve.service.search.SearchService;
import jakarta.servlet.http.HttpServletRequest;
import org.openapitools.jackson.nullable.JsonNullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The contract-generated explainable search endpoint. */
@RestController
@RequestMapping("/api")
public class SearchController implements SearchApi {

    private final SearchService search;
    private final ApiMapper mapper;
    private final HttpServletRequest request;

    public SearchController(
            SearchService search, ApiMapper mapper, HttpServletRequest request) {
        this.search = search;
        this.mapper = mapper;
        this.request = request;
    }

    @Override
    public ResponseEntity<SearchResponse> searchCases(SearchRequest searchRequest) {
        ComplaintInput query = new ComplaintInput(
                new CategoryValues(
                        searchRequest.getProduct(),
                        nullable(searchRequest.getSubProduct()),
                        searchRequest.getIssue(),
                        nullable(searchRequest.getSubIssue())),
                searchRequest.getNarrative(),
                null,
                null,
                null);
        SearchOutcome outcome = search.search(query, requestedLimit(searchRequest));
        return ResponseEntity.ok(mapper.search(outcome));
    }

    /**
     * The generated DTO initializes an omitted limit to 5. Passing that value would ignore the
     * configured default, so an omitted raw field is forwarded as {@code null}.
     */
    private Integer requestedLimit(SearchRequest searchRequest) {
        Object present = request.getAttribute(RequestRules.LIMIT_PRESENT_ATTRIBUTE);
        return Boolean.TRUE.equals(present) ? searchRequest.getLimit() : null;
    }

    private static <T> T nullable(JsonNullable<T> value) {
        return value == null || !value.isPresent() ? null : value.get();
    }
}
