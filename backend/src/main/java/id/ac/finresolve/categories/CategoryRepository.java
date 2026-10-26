package id.ac.finresolve.categories;

import id.ac.finresolve.db.Database;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Reads the sample category catalog from imported CFPB cases.
 *
 * <p>Analyst cases never expand the catalog. A standalone call gets a short read transaction, while
 * a call from a caller's {@link Database#read} or {@link Database#write} joins that transaction.
 */
public final class CategoryRepository {

    private static final Comparator<String> NULL_FIRST = (left, right) -> {
        if (left == null) {
            return right == null ? 0 : -1;
        }
        if (right == null) {
            return 1;
        }
        int leftIndex = 0;
        int rightIndex = 0;
        while (leftIndex < left.length() && rightIndex < right.length()) {
            int leftCodePoint = left.codePointAt(leftIndex);
            int rightCodePoint = right.codePointAt(rightIndex);
            if (leftCodePoint != rightCodePoint) {
                return Integer.compare(leftCodePoint, rightCodePoint);
            }
            leftIndex += Character.charCount(leftCodePoint);
            rightIndex += Character.charCount(rightCodePoint);
        }
        if (leftIndex < left.length()) {
            return 1;
        }
        if (rightIndex < right.length()) {
            return -1;
        }
        return 0;
    };

    private static final Comparator<CategoryValues> CATALOG_ORDER =
            Comparator.comparing(CategoryValues::product, NULL_FIRST)
                    .thenComparing(CategoryValues::subProduct, NULL_FIRST)
                    .thenComparing(CategoryValues::issue, NULL_FIRST)
                    .thenComparing(CategoryValues::subIssue, NULL_FIRST);

    private final Database database;

    public CategoryRepository(Database database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    public List<CategoryValues> list() {
        return database.read(() -> {
            List<CategoryValues> rows = database.jdbcTemplate().query(
                    """
                    SELECT DISTINCT product, sub_product, issue, sub_issue
                    FROM cases
                    WHERE source = 'CFPB'
                    """,
                    (resultSet, rowNumber) -> new CategoryValues(
                            resultSet.getString("product"),
                            resultSet.getString("sub_product"),
                            resultSet.getString("issue"),
                            resultSet.getString("sub_issue")));
            rows.sort(CATALOG_ORDER);
            return List.copyOf(rows);
        });
    }
}
