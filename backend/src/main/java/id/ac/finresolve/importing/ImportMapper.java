package id.ac.finresolve.importing;

import id.ac.finresolve.api.model.ImportMetadata;
import id.ac.finresolve.api.model.ImportReport;
import id.ac.finresolve.api.model.ImportRowError;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/** Maps an import batch and its row problems to the generated transport model. */
@Component
public final class ImportMapper {

    public ImportReport importReport(BatchRecord batch, boolean replayed) {
        ImportMetadata metadata = new ImportMetadata(
                batch.archiveUrl(),
                batch.sourceFileName(),
                batch.periodStart(),
                batch.periodEnd());
        List<ImportRowError> errors = new ArrayList<>(batch.errors().size());
        for (ImportRowProblem problem : batch.errors()) {
            errors.add(new ImportRowError(
                    problem.recordNumber(),
                    problem.field(),
                    ImportRowError.CodeEnum.fromValue(problem.code()),
                    problem.message()));
        }
        return new ImportReport(
                batch.id(),
                replayed,
                metadata,
                batch.csvSha256(),
                batch.processed(),
                batch.imported(),
                batch.duplicates(),
                batch.rejected(),
                errors,
                batch.rejected() > errors.size(),
                offset(batch.importedAt()));
    }

    private static OffsetDateTime offset(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
