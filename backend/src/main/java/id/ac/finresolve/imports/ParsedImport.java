package id.ac.finresolve.imports;

import id.ac.finresolve.cases.ComplaintInput;
import java.util.List;

/**
 * Fully materialized parse result, before duplicate classification.
 *
 * <p>{@code processed = rows.size() + rejected}. {@code rows} contains every valid record in
 * source order, including records whose complaint ID later proves to be a duplicate. Errors are
 * the first error per rejected record, capped by the parser for safe reporting.
 */
public record ParsedImport(
        String csvSha256,
        int processed,
        List<Row> rows,
        List<ImportRowProblem> errors,
        int rejected) {

    public ParsedImport {
        rows = rows == null ? List.of() : List.copyOf(rows);
        errors = errors == null ? List.of() : List.copyOf(errors);
    }

    public record Row(int recordNumber, String complaintId, ComplaintInput complaint) {
    }
}
