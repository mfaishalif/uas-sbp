package id.ac.finresolve.similarity;

import id.ac.finresolve.categories.CategoryValues;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Scores one category tuple and explains every field.
 *
 * <p>The denominator contains only fields present in the query. A query field that is absent is
 * reported as {@code NOT_USED}; a candidate field that is absent is {@code MISSING_IN_CASE}. Both
 * consume no contribution, but a provided candidate field that differs still counts against the
 * query's used weight. Effective weights are normalized over the used fields so contributions sum
 * to the category score.
 */
public final class CategoryScorer {

    public static final String MATCH = "MATCH";
    public static final String MISMATCH = "MISMATCH";
    public static final String MISSING_IN_CASE = "MISSING_IN_CASE";
    public static final String NOT_USED = "NOT_USED";

    public CategoryScore score(
            CategoryValues query, CategoryValues candidate, SimilaritySettings settings) {
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(settings, "settings");

        List<Field> fields = List.of(
                new Field(
                        "product",
                        query.product(),
                        candidate.product(),
                        settings.productWeight()),
                new Field(
                        "subProduct",
                        query.subProduct(),
                        candidate.subProduct(),
                        settings.subProductWeight()),
                new Field(
                        "issue",
                        query.issue(),
                        candidate.issue(),
                        settings.issueWeight()),
                new Field(
                        "subIssue",
                        query.subIssue(),
                        candidate.subIssue(),
                        settings.subIssueWeight()));

        double usedWeight = fields.stream()
                .filter(field -> hasValue(field.queryValue()))
                .mapToDouble(Field::configuredWeight)
                .sum();

        List<FieldMatch> matches = new ArrayList<>(fields.size());
        double score = 0.0;
        for (Field field : fields) {
            boolean used = hasValue(field.queryValue());
            String status = status(field.queryValue(), field.caseValue());
            double effectiveWeight = used && usedWeight > 0.0
                    ? field.configuredWeight() / usedWeight
                    : 0.0;
            double contribution = MATCH.equals(status) ? effectiveWeight : 0.0;
            score += contribution;
            matches.add(new FieldMatch(
                    field.name(),
                    field.queryValue(),
                    field.caseValue(),
                    status,
                    field.configuredWeight(),
                    effectiveWeight,
                    contribution));
        }

        return new CategoryScore(clamp(score), matches);
    }

    private static String status(String queryValue, String caseValue) {
        if (!hasValue(queryValue)) {
            return NOT_USED;
        }
        if (!hasValue(caseValue)) {
            return MISSING_IN_CASE;
        }
        return queryValue.strip().equals(caseValue.strip()) ? MATCH : MISMATCH;
    }

    private static boolean hasValue(String value) {
        return value != null && !value.isBlank();
    }

    private static double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private record Field(
            String name,
            String queryValue,
            String caseValue,
            double configuredWeight) {
    }

    public record CategoryScore(double score, List<FieldMatch> fields) {

        public CategoryScore {
            fields = List.copyOf(fields == null ? List.of() : fields);
        }
    }

    public record FieldMatch(
            String field,
            String queryValue,
            String caseValue,
            String status,
            double configuredWeight,
            double effectiveWeight,
            double contribution) {
    }
}
