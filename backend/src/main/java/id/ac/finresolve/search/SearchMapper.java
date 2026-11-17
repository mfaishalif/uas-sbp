package id.ac.finresolve.search;

import id.ac.finresolve.api.model.CategoryExplanation;
import id.ac.finresolve.api.model.SearchConfiguration;
import id.ac.finresolve.api.model.SearchConfigurationCategoryFieldWeights;
import id.ac.finresolve.api.model.SearchMatch;
import id.ac.finresolve.api.model.SearchResponse;
import id.ac.finresolve.api.model.TermContribution;
import id.ac.finresolve.cases.CaseMapper;
import id.ac.finresolve.similarity.CategoryScorer;
import id.ac.finresolve.similarity.SimilarityResult;
import id.ac.finresolve.similarity.SimilaritySettings;
import id.ac.finresolve.similarity.TfidfSnapshot;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/** Maps an explainable search outcome to the generated transport model. */
@Component
public final class SearchMapper {

    private final CaseMapper cases;

    public SearchMapper(CaseMapper cases) {
        this.cases = cases;
    }

    public SearchResponse search(SearchOutcome outcome) {
        List<SearchMatch> matches = new ArrayList<>(outcome.results().size());
        for (SimilarityResult result : outcome.results()) {
            matches.add(searchMatch(result));
        }
        return new SearchResponse(
                SearchResponse.StatusEnum.fromValue(outcome.status()),
                outcome.message(),
                matches,
                outcome.results().size(),
                outcome.limit(),
                outcome.corpusSize(),
                searchConfiguration(outcome.configuration()),
                outcome.warnings());
    }

    private SearchMatch searchMatch(SimilarityResult result) {
        List<CategoryExplanation> explanations = new ArrayList<>(
                result.categoryExplanation().size());
        for (CategoryScorer.FieldMatch field : result.categoryExplanation()) {
            explanations.add(new CategoryExplanation(
                    CategoryExplanation.FieldEnum.fromValue(field.field()),
                    field.queryValue(),
                    field.caseValue(),
                    CategoryExplanation.StatusEnum.fromValue(field.status()),
                    decimal(field.configuredWeight()),
                    decimal(field.effectiveWeight()),
                    decimal(field.contribution())));
        }

        List<TermContribution> terms = new ArrayList<>(result.sharedTerms().size());
        for (TfidfSnapshot.Term term : result.sharedTerms()) {
            terms.add(new TermContribution(term.term(), decimal(term.contribution())));
        }

        return new SearchMatch(
                cases.caseDetail(result.caseRecord()),
                decimal(result.score()),
                decimal(result.categoryScore()),
                decimal(result.textScore()),
                explanations,
                terms);
    }

    private static SearchConfiguration searchConfiguration(
            SimilaritySettings settings) {
        return new SearchConfiguration(
                decimal(settings.categoryWeight()),
                decimal(settings.textWeight()),
                new SearchConfigurationCategoryFieldWeights(
                        decimal(settings.productWeight()),
                        decimal(settings.subProductWeight()),
                        decimal(settings.issueWeight()),
                        decimal(settings.subIssueWeight())),
                decimal(settings.threshold()),
                SearchConfiguration.RequiresTextOverlapEnum.TRUE,
                settings.algorithmVersion());
    }

    private static BigDecimal decimal(double value) {
        return BigDecimal.valueOf(value);
    }
}
