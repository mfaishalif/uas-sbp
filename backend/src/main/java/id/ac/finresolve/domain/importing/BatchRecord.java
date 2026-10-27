package id.ac.finresolve.domain.importing;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * An import batch plus the original report values needed to replay that report verbatim.
 */
public record BatchRecord(
        UUID id,
        String archiveUrl,
        String sourceFileName,
        LocalDate periodStart,
        LocalDate periodEnd,
        String csvSha256,
        Instant importedAt,
        int processed,
        int imported,
        int duplicates,
        int rejected,
        List<ImportRowProblem> errors) {

    public BatchRecord {
        errors = errors == null ? List.of() : List.copyOf(errors);
    }
}
