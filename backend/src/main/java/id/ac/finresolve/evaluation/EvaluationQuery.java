package id.ac.finresolve.evaluation;

import id.ac.finresolve.categories.CategoryValues;
import java.util.List;
import java.util.Locale;

/** One frozen evaluation query. */
public record EvaluationQuery(
        String queryId,
        Kind kind,
        CategoryValues categories,
        String narrative,
        String scenario) {

    public EvaluationQuery {
        if (queryId == null || queryId.isBlank()) {
            throw new IllegalArgumentException("queryId is required");
        }
        if (kind == null) {
            throw new IllegalArgumentException("kind is required for " + queryId);
        }
        if (categories == null) {
            throw new IllegalArgumentException("categories are required for " + queryId);
        }
        if (narrative == null || narrative.isBlank()) {
            throw new IllegalArgumentException("narrative is required for " + queryId);
        }
    }

    public enum Kind {
        COMPLAINT,
        NEGATIVE;

        public static Kind parse(String value) {
            if (value == null) {
                throw new IllegalArgumentException("kind is required");
            }
            return switch (value.strip().toLowerCase(Locale.ROOT)) {
                case "complaint" -> COMPLAINT;
                case "negative" -> NEGATIVE;
                default -> throw new IllegalArgumentException(
                        "kind must be complaint or negative: " + value);
            };
        }

        public String csvValue() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public List<String> categoryValues() {
        return List.of(
                categories.product(),
                categories.subProduct(),
                categories.issue(),
                categories.subIssue());
    }
}
