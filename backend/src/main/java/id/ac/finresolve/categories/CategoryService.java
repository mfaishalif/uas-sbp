package id.ac.finresolve.categories;

import id.ac.finresolve.cases.InputRules;
import id.ac.finresolve.http.BusinessException;
import id.ac.finresolve.http.BusinessException.Problem;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Validates requested categories against the current CFPB sample catalog and returns its labels.
 *
 * <p>Product and issue are required. Subproduct may come from any tuple with the same product;
 * subissue may come from any tuple with the same product and issue. The two optional branches are
 * independent, so they do not have to appear together in one source tuple.
 */
public final class CategoryService {

    private static final String EMPTY_CATALOG_CODE = "EMPTY_CATEGORY_CATALOG";

    private final CategoryRepository repository;

    public CategoryService(CategoryRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    public CategoryValues canonicalize(CategoryValues requested) {
        return canonicalize(repository.list(), requested);
    }

    /**
     * Canonicalizes against an already-materialized catalog so search can read the corpus and
     * catalog in one short transaction.
     */
    public CategoryValues canonicalize(List<CategoryValues> catalog, CategoryValues requested) {
        Objects.requireNonNull(catalog, "catalog");
        if (catalog.isEmpty()) {
            throw new BusinessException(
                    409,
                    EMPTY_CATALOG_CODE,
                    "Katalog kategori sampel masih kosong. Impor sampel CFPB terlebih dahulu.",
                    List.of());
        }
        if (requested == null) {
            throw invalid(
                    List.of(new Problem(
                            "categories", "REQUIRED", "Kategori pengaduan wajib diisi.")));
        }

        String product = InputRules.normalizeCategory(requested.product());
        String subProduct = InputRules.normalizeCategory(requested.subProduct());
        String issue = InputRules.normalizeCategory(requested.issue());
        String subIssue = InputRules.normalizeCategory(requested.subIssue());

        List<Problem> problems = new ArrayList<>();
        String canonicalProduct = firstProduct(catalog, product);
        if (product == null) {
            problems.add(new Problem("product", "REQUIRED", "Produk wajib diisi."));
            throw invalid(problems);
        }
        if (canonicalProduct == null) {
            problems.add(new Problem(
                    "product", "INVALID_VALUE", "Nilai product tidak tersedia pada katalog."));
            throw invalid(problems);
        }

        String canonicalIssue = null;
        if (issue == null) {
            problems.add(new Problem("issue", "REQUIRED", "Masalah wajib diisi."));
        } else {
            canonicalIssue = firstIssue(catalog, product, issue);
            if (canonicalIssue == null) {
                problems.add(new Problem(
                        "issue",
                        "INVALID_VALUE",
                        "Nilai issue tidak tersedia untuk produk tersebut."));
            }
        }

        String canonicalSubProduct = null;
        if (subProduct != null) {
            canonicalSubProduct = firstSubProduct(catalog, product, subProduct);
            if (canonicalSubProduct == null) {
                problems.add(new Problem(
                        "subProduct",
                        "INVALID_VALUE",
                        "Nilai subProduct tidak tersedia untuk produk tersebut."));
            }
        }

        String canonicalSubIssue = null;
        if (subIssue != null) {
            if (canonicalIssue == null) {
                problems.add(new Problem(
                        "subIssue",
                        "INVALID_VALUE",
                        "Nilai subIssue membutuhkan produk dan masalah yang sesuai."));
            } else {
                canonicalSubIssue = firstSubIssue(catalog, product, issue, subIssue);
                if (canonicalSubIssue == null) {
                    problems.add(new Problem(
                            "subIssue",
                            "INVALID_VALUE",
                            "Nilai subIssue tidak tersedia untuk produk dan masalah tersebut."));
                }
            }
        }

        if (!problems.isEmpty()) {
            throw invalid(problems);
        }
        return new CategoryValues(
                canonicalProduct, canonicalSubProduct, canonicalIssue, canonicalSubIssue);
    }

    private static String firstProduct(List<CategoryValues> catalog, String product) {
        if (product == null) {
            return null;
        }
        for (CategoryValues tuple : catalog) {
            if (same(tuple.product(), product)) {
                return tuple.product();
            }
        }
        return null;
    }

    private static String firstSubProduct(
            List<CategoryValues> catalog, String product, String subProduct) {
        for (CategoryValues tuple : catalog) {
            if (same(tuple.product(), product) && same(tuple.subProduct(), subProduct)) {
                return tuple.subProduct();
            }
        }
        return null;
    }

    private static String firstIssue(
            List<CategoryValues> catalog, String product, String issue) {
        for (CategoryValues tuple : catalog) {
            if (same(tuple.product(), product) && same(tuple.issue(), issue)) {
                return tuple.issue();
            }
        }
        return null;
    }

    private static String firstSubIssue(
            List<CategoryValues> catalog, String product, String issue, String subIssue) {
        for (CategoryValues tuple : catalog) {
            if (same(tuple.product(), product)
                    && same(tuple.issue(), issue)
                    && same(tuple.subIssue(), subIssue)) {
                return tuple.subIssue();
            }
        }
        return null;
    }

    private static boolean same(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        String normalizedLeft = InputRules.normalizeCategory(left);
        String normalizedRight = InputRules.normalizeCategory(right);
        return normalizedLeft != null
                && normalizedRight != null
                && normalizedLeft.toLowerCase(Locale.ROOT)
                        .equals(normalizedRight.toLowerCase(Locale.ROOT));
    }

    private static BusinessException invalid(List<Problem> problems) {
        return new BusinessException(
                400,
                "INVALID_CATEGORY",
                "Kategori tidak sesuai dengan katalog sampel.",
                problems);
    }
}
