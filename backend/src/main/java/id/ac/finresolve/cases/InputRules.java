package id.ac.finresolve.cases;

import id.ac.finresolve.categories.CategoryValues;
import id.ac.finresolve.http.BusinessException;
import id.ac.finresolve.http.BusinessException.Problem;
import id.ac.finresolve.similarity.EnglishTokenizer;
import java.util.ArrayList;
import java.util.List;

/**
 * Shape and text validation before any catalog lookup.
 *
 * <p>The import path must accept categories that are not in the current sample catalog, so this
 * class never reads {@code CategoryRepository}. Limits are measured in Unicode code points, not
 * UTF-16 code units, so supplementary characters count once.
 */
public final class InputRules {

    public static final int MAX_CATEGORY_CODE_POINTS = 500;
    public static final int MAX_COMPANY_CODE_POINTS = 500;
    public static final int MAX_COMPANY_RESPONSE_CODE_POINTS = 2_000;
    public static final int MAX_NARRATIVE_CODE_POINTS = 50_000;

    private static final EnglishTokenizer TOKENIZER = new EnglishTokenizer();

    /** Kept for callers that prefer an injected-looking collaborator. */
    public InputRules() {
    }

    /**
     * Trims and collapses every Unicode whitespace run to one regular space.
     *
     * @return {@code null} for null or blank input
     */
    public static String normalizeCategory(String value) {
        String trimmed = trimUnicode(value);
        if (trimmed == null || trimmed.isEmpty()) {
            return null;
        }
        return collapseWhitespace(trimmed);
    }

    /** Validates and normalizes complaint shape without consulting the sample catalog. */
    public static ComplaintInput complaint(ComplaintInput input) {
        if (input == null) {
            throw validationError(
                    "Input pengaduan wajib diisi.",
                    List.of(new Problem("body", "REQUIRED", "Badan permintaan wajib diisi.")));
        }

        List<Problem> problems = new ArrayList<>();
        CategoryValues categories = normalizeCategories(input.categories(), problems);

        String narrative = trimUnicode(input.narrative());
        if (narrative == null || narrative.isEmpty()) {
            problems.add(new Problem("narrative", "REQUIRED", "Narasi wajib diisi."));
            narrative = null;
        } else if (codePointLength(narrative) > MAX_NARRATIVE_CODE_POINTS) {
            problems.add(new Problem(
                    "narrative",
                    "TOO_LONG",
                    "Narasi maksimal 50.000 karakter."));
        } else if (TOKENIZER.tokens(narrative).isEmpty()) {
            problems.add(new Problem(
                    "narrative",
                    "NO_MEANINGFUL_TOKENS",
                    "Narasi harus mengandung setidaknya satu kata bermakna."));
        }

        String company = optionalText(
                input.company(),
                "company",
                MAX_COMPANY_CODE_POINTS,
                "Perusahaan maksimal 500 karakter.",
                problems);
        String companyResponse = optionalText(
                input.companyResponse(),
                "companyResponse",
                MAX_COMPANY_RESPONSE_CODE_POINTS,
                "Respons perusahaan maksimal 2.000 karakter.",
                problems);

        if (!problems.isEmpty()) {
            throw validationError("Input pengaduan tidak valid.", problems);
        }

        return new ComplaintInput(
                categories, narrative, input.receivedDate(), company, companyResponse);
    }

    private static CategoryValues normalizeCategories(
            CategoryValues requested, List<Problem> problems) {
        if (requested == null) {
            problems.add(new Problem(
                    "categories", "REQUIRED", "Kategori pengaduan wajib diisi."));
            return null;
        }

        String product = normalizeCategory(requested.product());
        String subProduct = normalizeCategory(requested.subProduct());
        String issue = normalizeCategory(requested.issue());
        String subIssue = normalizeCategory(requested.subIssue());

        if (product == null) {
            problems.add(new Problem("product", "REQUIRED", "Produk wajib diisi."));
        } else {
            checkCategoryLength(product, "product", "Produk", problems);
        }

        if (issue == null) {
            problems.add(new Problem("issue", "REQUIRED", "Masalah wajib diisi."));
        } else {
            checkCategoryLength(issue, "issue", "Masalah", problems);
        }

        if (subProduct != null) {
            if (product == null) {
                problems.add(new Problem(
                        "subProduct",
                        "REQUIRED",
                        "Subproduk membutuhkan produk induk."));
            }
            checkCategoryLength(subProduct, "subProduct", "Subproduk", problems);
        }

        if (subIssue != null) {
            if (product == null || issue == null) {
                problems.add(new Problem(
                        "subIssue",
                        "REQUIRED",
                        "Submasalah membutuhkan produk dan masalah induk."));
            }
            checkCategoryLength(subIssue, "subIssue", "Submasalah", problems);
        }

        return new CategoryValues(product, subProduct, issue, subIssue);
    }

    private static void checkCategoryLength(
            String value, String field, String label, List<Problem> problems) {
        if (codePointLength(value) > MAX_CATEGORY_CODE_POINTS) {
            problems.add(new Problem(
                    field,
                    "TOO_LONG",
                    label + " maksimal 500 karakter."));
        }
    }

    private static String optionalText(
            String value,
            String field,
            int maxCodePoints,
            String tooLongMessage,
            List<Problem> problems) {
        String trimmed = trimUnicode(value);
        if (trimmed == null || trimmed.isEmpty()) {
            return null;
        }
        if (codePointLength(trimmed) > maxCodePoints) {
            problems.add(new Problem(field, "TOO_LONG", tooLongMessage));
        }
        return trimmed;
    }

    private static BusinessException validationError(String message, List<Problem> problems) {
        return new BusinessException(400, "VALIDATION_ERROR", message, problems);
    }

    private static int codePointLength(String value) {
        return value.codePointCount(0, value.length());
    }

    /** Trims every Unicode whitespace code point, including no-break space. */
    private static String trimUnicode(String value) {
        if (value == null) {
            return null;
        }
        int start = 0;
        int end = value.length();
        while (start < end) {
            int codePoint = value.codePointAt(start);
            if (!isWhitespace(codePoint)) {
                break;
            }
            start += Character.charCount(codePoint);
        }
        while (end > start) {
            int codePoint = value.codePointBefore(end);
            if (!isWhitespace(codePoint)) {
                break;
            }
            end -= Character.charCount(codePoint);
        }
        return value.substring(start, end);
    }

    private static String collapseWhitespace(String value) {
        StringBuilder collapsed = new StringBuilder(value.length());
        boolean pendingSpace = false;
        int index = 0;
        while (index < value.length()) {
            int codePoint = value.codePointAt(index);
            index += Character.charCount(codePoint);
            if (isWhitespace(codePoint)) {
                if (collapsed.length() > 0) {
                    pendingSpace = true;
                }
                continue;
            }
            if (pendingSpace) {
                collapsed.append(' ');
                pendingSpace = false;
            }
            collapsed.appendCodePoint(codePoint);
        }
        return collapsed.toString();
    }

    private static boolean isWhitespace(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
    }
}
