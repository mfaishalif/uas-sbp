package id.ac.finresolve.domain.similarity;

import id.ac.finresolve.domain.cases.CaseRecord;
import java.util.List;

/**
 * One ranked case with complete score explanations.
 */
public record SimilarityResult(
        CaseRecord caseRecord,
        double score,
        double categoryScore,
        double textScore,
        List<CategoryScorer.FieldMatch> categoryExplanation,
        List<TfidfSnapshot.Term> sharedTerms) {

    public SimilarityResult {
        categoryExplanation = List.copyOf(
                categoryExplanation == null ? List.of() : categoryExplanation);
        sharedTerms = List.copyOf(sharedTerms == null ? List.of() : sharedTerms);
    }
}
