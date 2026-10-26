package id.ac.finresolve.imports;

import id.ac.finresolve.db.Database;
import id.ac.finresolve.db.Timestamps;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/**
 * Batch persistence and replay lookup.
 *
 * <p>Every operation joins the caller's transaction through {@link Database}. The composite replay
 * key is the CSV hash plus all four normalized metadata values, matching the unique constraint in
 * the schema.
 */
@Repository
public class ImportRepository {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String SELECT_BATCH =
            """
            SELECT
                id, archive_url, source_file_name, period_start, period_end, csv_sha256,
                imported_at, processed, imported, duplicates, rejected, errors_json
            FROM import_batches
            """;

    private final Database database;

    public ImportRepository(Database database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    public Optional<BatchRecord> findReplay(String sha256, ImportMetadataValue metadata) {
        Objects.requireNonNull(sha256, "sha256");
        Objects.requireNonNull(metadata, "metadata");
        return database.read(() -> database.jdbcTemplate().query(
                        SELECT_BATCH
                                + """
                                 WHERE csv_sha256 = ?
                                   AND archive_url = ?
                                   AND source_file_name = ?
                                   AND period_start = ?
                                   AND period_end = ?
                                """,
                        this::mapBatch,
                        sha256,
                        metadata.archiveUrl(),
                        metadata.sourceFileName(),
                        metadata.periodStart().toString(),
                        metadata.periodEnd().toString())
                .stream()
                .findFirst());
    }

    public void insert(BatchRecord batch) {
        Objects.requireNonNull(batch, "batch");
        database.write(() -> {
            database.jdbcTemplate().update(
                    """
                    INSERT INTO import_batches (
                        id, archive_url, source_file_name, period_start, period_end, csv_sha256,
                        imported_at, processed, imported, duplicates, rejected, errors_json
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    batch.id().toString(),
                    batch.archiveUrl(),
                    batch.sourceFileName(),
                    batch.periodStart().toString(),
                    batch.periodEnd().toString(),
                    batch.csvSha256(),
                    Timestamps.format(batch.importedAt()),
                    batch.processed(),
                    batch.imported(),
                    batch.duplicates(),
                    batch.rejected(),
                    JSON.writeValueAsString(batch.errors()));
            return null;
        });
    }

    public Set<String> existingComplaintIds() {
        return database.read(() -> new HashSet<>(database.jdbcTemplate().queryForList(
                "SELECT complaint_id FROM cases WHERE complaint_id IS NOT NULL", String.class)));
    }

    private BatchRecord mapBatch(java.sql.ResultSet resultSet, int rowNumber)
            throws java.sql.SQLException {
        ImportRowProblem[] errors =
                JSON.readValue(resultSet.getString("errors_json"), ImportRowProblem[].class);
        List<ImportRowProblem> materialized = errors == null ? List.of() : List.of(errors);
        return new BatchRecord(
                UUID.fromString(resultSet.getString("id")),
                resultSet.getString("archive_url"),
                resultSet.getString("source_file_name"),
                java.time.LocalDate.parse(resultSet.getString("period_start")),
                java.time.LocalDate.parse(resultSet.getString("period_end")),
                resultSet.getString("csv_sha256"),
                Timestamps.parse(resultSet.getString("imported_at")),
                resultSet.getInt("processed"),
                resultSet.getInt("imported"),
                resultSet.getInt("duplicates"),
                resultSet.getInt("rejected"),
                materialized);
    }
}
