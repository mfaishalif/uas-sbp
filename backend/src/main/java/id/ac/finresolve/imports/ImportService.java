package id.ac.finresolve.imports;

import id.ac.finresolve.cases.CaseRecord;
import id.ac.finresolve.cases.CaseRepository;
import id.ac.finresolve.cases.ComplaintInput;
import id.ac.finresolve.db.Database;
import id.ac.finresolve.http.BusinessException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Parses outside the write transaction, then persists one batch and all of its cases atomically.
 *
 * <p>Validation happens before duplicate classification. Duplicate checks consider both the
 * database and earlier valid records in the same file, so the first valid occurrence wins. A
 * replay is detected inside the same serialized write transaction and never touches cases.
 */
@Service
public class ImportService {

    static final int MAX_CASES = 5_000;

    private final ImportRepository imports;
    private final CaseRepository cases;
    private final Database database;
    private final Clock clock;
    private final Supplier<UUID> idSupplier;
    private final CfpbCsvParser parser = new CfpbCsvParser();

    @Autowired
    public ImportService(ImportRepository imports, CaseRepository cases, Database database) {
        this(imports, cases, database, Clock.systemUTC(), UUID::randomUUID);
    }

    ImportService(
            ImportRepository imports,
            CaseRepository cases,
            Database database,
            Clock clock,
            Supplier<UUID> idSupplier) {
        this.imports = Objects.requireNonNull(imports, "imports");
        this.cases = Objects.requireNonNull(cases, "cases");
        this.database = Objects.requireNonNull(database, "database");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.idSupplier = Objects.requireNonNull(idSupplier, "idSupplier");
    }

    public Result importCsv(InputStream input, ImportMetadataValue metadata) {
        ParsedImport parsed = parser.parse(input, metadata);
        return database.write(() -> persist(parsed, metadata));
    }

    private Result persist(ParsedImport parsed, ImportMetadataValue metadata) {
        Optional<BatchRecord> replay = imports.findReplay(parsed.csvSha256(), metadata);
        if (replay.isPresent()) {
            return new Result(replay.get(), true);
        }

        Set<String> existing = imports.existingComplaintIds();
        Set<String> seen = new HashSet<>();
        List<CaseRecord> newCases = new ArrayList<>();
        Instant now = clock.instant();
        UUID batchId = idSupplier.get();

        for (ParsedImport.Row row : parsed.rows()) {
            if (existing.contains(row.complaintId()) || !seen.add(row.complaintId())) {
                continue;
            }
            newCases.add(toCase(row, metadata, batchId, parsed.csvSha256(), now));
        }

        long currentCount = cases.count();
        if (currentCount + newCases.size() > MAX_CASES) {
            throw new BusinessException(
                    409,
                    "KNOWLEDGE_BASE_LIMIT_EXCEEDED",
                    "Basis kasus mencapai batas 5.000; impor dibatalkan seluruhnya.",
                    List.of());
        }

        int duplicates = parsed.rows().size() - newCases.size();
        BatchRecord batch = new BatchRecord(
                batchId,
                metadata.archiveUrl(),
                metadata.sourceFileName(),
                metadata.periodStart(),
                metadata.periodEnd(),
                parsed.csvSha256(),
                now,
                parsed.processed(),
                newCases.size(),
                duplicates,
                parsed.rejected(),
                parsed.errors());

        imports.insert(batch);
        for (CaseRecord record : newCases) {
            cases.insert(record);
        }
        return new Result(batch, false);
    }

    private CaseRecord toCase(
            ParsedImport.Row row,
            ImportMetadataValue metadata,
            UUID batchId,
            String csvSha256,
            Instant now) {
        ComplaintInput complaint = row.complaint();
        return new CaseRecord(
                idSupplier.get(),
                CaseRecord.Source.CFPB,
                complaint.categories(),
                complaint.narrative(),
                complaint.receivedDate(),
                complaint.company(),
                complaint.companyResponse(),
                now,
                now,
                new CaseRecord.Provenance(
                        batchId,
                        row.complaintId(),
                        metadata.archiveUrl(),
                        metadata.sourceFileName(),
                        metadata.periodStart(),
                        metadata.periodEnd(),
                        csvSha256),
                null,
                List.of());
    }

    public record Result(BatchRecord batch, boolean replayed) {
    }
}
