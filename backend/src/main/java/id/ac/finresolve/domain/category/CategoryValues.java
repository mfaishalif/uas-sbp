package id.ac.finresolve.domain.category;

/**
 * One category tuple used by both imported CFPB cases and analyst-retained cases.
 *
 * <p>Subcategories are nullable. Category normalization and catalog membership are handled by
 * the category service, not by this storage record.
 */
public record CategoryValues(String product, String subProduct, String issue, String subIssue) {
}
