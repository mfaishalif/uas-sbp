package id.ac.finresolve.service.search;

import id.ac.finresolve.domain.similarity.SimilarityResult;
import id.ac.finresolve.domain.similarity.SimilaritySettings;
import java.util.List;

/** One successful search response, independent of transport models. */
public record SearchOutcome(
        String status,
        String message,
        List<SimilarityResult> results,
        int limit,
        int corpusSize,
        SimilaritySettings configuration,
        List<String> warnings) {

    public SearchOutcome {
        results = results == null ? List.of() : List.copyOf(results);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
