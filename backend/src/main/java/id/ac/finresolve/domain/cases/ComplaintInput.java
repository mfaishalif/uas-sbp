package id.ac.finresolve.domain.cases;

import id.ac.finresolve.domain.category.CategoryValues;
import java.time.LocalDate;

/**
 * Shape-valid complaint input shared by import, search, and analyst retention.
 *
 * <p>Catalog membership is intentionally not established here; the import path must accept novel
 * CFPB categories.
 */
public record ComplaintInput(
        CategoryValues categories,
        String narrative,
        LocalDate receivedDate,
        String company,
        String companyResponse) {
}
