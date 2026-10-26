package id.ac.finresolve.cases;

import id.ac.finresolve.categories.CategoryValues;
import id.ac.finresolve.db.Database;
import id.ac.finresolve.db.Timestamps;
import java.math.BigInteger;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

/**
 * Prepared-SQL case reads and writes.
 *
 * <p>Every public operation joins the caller's transaction through {@link Database}. A standalone
 * call still gets a short transaction, so a repository write cannot leave a case without its notes
 * or references.
 */
public final class CaseRepository {

    /** Shared demo capacity for imported and manually retained cases. */
    public static final int MAX_CASES = 5_000;

    private static final String CASE_SELECT =
            """
            SELECT
                c.id AS id,
                c.source AS source,
                c.complaint_id AS complaint_id,
                c.batch_id AS batch_id,
                c.received_date AS received_date,
                c.product AS product,
                c.sub_product AS sub_product,
                c.issue AS issue,
                c.sub_issue AS sub_issue,
                c.narrative AS narrative,
                c.company AS company,
                c.company_response AS company_response,
                c.created_at AS created_at,
                c.updated_at AS updated_at,
                b.archive_url AS archive_url,
                b.source_file_name AS source_file_name,
                b.period_start AS period_start,
                b.period_end AS period_end,
                b.csv_sha256 AS csv_sha256,
                n.handling_notes AS handling_notes,
                n.outcome_assessment AS outcome_assessment,
                n.evaluation_notes AS evaluation_notes
            FROM cases c
            LEFT JOIN import_batches b ON b.id = c.batch_id
            LEFT JOIN analyst_notes n ON n.case_id = c.id
            """;

    private static final String STABLE_ORDER = " ORDER BY c.created_at DESC, c.id ASC";

    private final Database database;

    public CaseRepository(Database database) {
        this.database = database;
    }

    public Optional<CaseRecord> find(UUID id) {
        Objects.requireNonNull(id, "id");
        return database.read(() -> {
            List<CaseRecord> rows = database.jdbcTemplate().query(
                    CASE_SELECT + " WHERE c.id = ?", this::mapCase, id.toString());
            if (rows.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(attachReferences(rows).get(0));
        });
    }

    public Page page(int page, int size, CaseRecord.Source source) {
        if (page < 0) {
            throw new IllegalArgumentException("page must not be negative");
        }
        if (size <= 0) {
            throw new IllegalArgumentException("size must be positive");
        }
        long offset = Math.multiplyExact((long) page, (long) size);

        return database.read(() -> {
            JdbcTemplate jdbc = database.jdbcTemplate();
            long total;
            List<CaseRecord> rows;
            if (source == null) {
                total = countRows(jdbc, null);
                rows = jdbc.query(
                        CASE_SELECT + STABLE_ORDER + " LIMIT ? OFFSET ?",
                        this::mapCase,
                        new Object[] {size, offset});
            } else {
                total = countRows(jdbc, source);
                rows = jdbc.query(
                        CASE_SELECT + " WHERE c.source = ?" + STABLE_ORDER + " LIMIT ? OFFSET ?",
                        this::mapCase,
                        new Object[] {source.name(), size, offset});
            }
            return new Page(attachReferences(rows), page, size, total);
        });
    }

    public List<CaseRecord> snapshot() {
        return database.read(() -> attachReferences(database.jdbcTemplate().query(
                CASE_SELECT + STABLE_ORDER, this::mapCase)));
    }

    public long count() {
        return database.read(() -> countRows(database.jdbcTemplate(), null));
    }

    public void insert(CaseRecord record) {
        validateRecord(record);
        database.write(() -> {
            validateReferencesExist(record);
            if (record.source() == CaseRecord.Source.CFPB) {
                requireBatch(record.provenance().batchId());
            }
            insertCase(record);
            insertNotes(record);
            insertReferences(record);
            return null;
        });
    }

    public void replaceAnalyst(CaseRecord record) {
        Objects.requireNonNull(record, "record");
        if (record.source() != CaseRecord.Source.ANALYST) {
            throw new IllegalArgumentException("replaceAnalyst requires an ANALYST case.");
        }
        validateRecord(record);

        database.write(() -> {
            CaseRecord existing = find(record.id())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "ANALYST case not found: " + record.id()));
            if (existing.source() != CaseRecord.Source.ANALYST) {
                throw new IllegalArgumentException(
                        "replaceAnalyst requires an existing ANALYST case.");
            }
            if (record.updatedAt().isBefore(existing.createdAt())) {
                throw new IllegalArgumentException(
                        "updatedAt must not be before the preserved createdAt.");
            }
            validateReferencesExist(record);

            int updated = database.jdbcTemplate().update(
                    """
                    UPDATE cases
                    SET received_date = ?, product = ?, sub_product = ?, issue = ?, sub_issue = ?,
                        narrative = ?, company = ?, company_response = ?, updated_at = ?
                    WHERE id = ? AND source = 'ANALYST'
                    """,
                    date(record.receivedDate()),
                    record.categories().product(),
                    record.categories().subProduct(),
                    record.categories().issue(),
                    record.categories().subIssue(),
                    record.narrative(),
                    record.company(),
                    record.companyResponse(),
                    Timestamps.format(record.updatedAt()),
                    record.id().toString());
            if (updated != 1) {
                throw new IllegalStateException("ANALYST case disappeared during replacement.");
            }

            database.jdbcTemplate().update(
                    "DELETE FROM analyst_notes WHERE case_id = ?", record.id().toString());
            insertNotes(record);
            database.jdbcTemplate().update(
                    "DELETE FROM case_references WHERE case_id = ?", record.id().toString());
            insertReferences(record);
            return null;
        });
    }

    private long countRows(JdbcTemplate jdbc, CaseRecord.Source source) {
        Long total = source == null
                ? jdbc.queryForObject("SELECT COUNT(*) FROM cases", Long.class)
                : jdbc.queryForObject(
                        "SELECT COUNT(*) FROM cases WHERE source = ?", Long.class, source.name());
        return total == null ? 0L : total;
    }

    private void validateRecord(CaseRecord record) {
        Objects.requireNonNull(record, "record");
        Objects.requireNonNull(record.id(), "id");
        Objects.requireNonNull(record.source(), "source");
        Objects.requireNonNull(record.categories(), "categories");
        requireText(record.categories().product(), "product");
        requireText(record.categories().issue(), "issue");
        requireText(record.narrative(), "narrative");
        Objects.requireNonNull(record.createdAt(), "createdAt");
        Objects.requireNonNull(record.updatedAt(), "updatedAt");
        if (record.updatedAt().isBefore(record.createdAt())) {
            throw new IllegalArgumentException("updatedAt must not be before createdAt.");
        }

        List<UUID> references = record.referenceCaseIds();
        if (references.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("referenceCaseIds must not contain nulls.");
        }
        if (new HashSet<>(references).size() != references.size()) {
            throw new IllegalArgumentException("referenceCaseIds must be distinct.");
        }
        if (references.contains(record.id())) {
            throw new IllegalArgumentException("A case cannot reference itself.");
        }

        switch (record.source()) {
            case CFPB -> validateCfpb(record);
            case ANALYST -> validateAnalyst(record);
        }
    }

    private static void validateCfpb(CaseRecord record) {
        if (record.notes() != null) {
            throw new IllegalArgumentException("CFPB cases must not have analyst notes.");
        }
        if (!record.referenceCaseIds().isEmpty()) {
            throw new IllegalArgumentException("CFPB cases must not have reference cases.");
        }
        if (record.provenance() == null) {
            throw new IllegalArgumentException("CFPB cases require provenance.");
        }
        CaseRecord.Provenance provenance = record.provenance();
        requireNonNull(provenance.batchId(), "provenance.batchId");
        requireText(provenance.complaintId(), "provenance.complaintId");
        requireText(provenance.archiveUrl(), "provenance.archiveUrl");
        requireText(provenance.sourceFileName(), "provenance.sourceFileName");
        requireNonNull(provenance.periodStart(), "provenance.periodStart");
        requireNonNull(provenance.periodEnd(), "provenance.periodEnd");
        requireText(provenance.csvSha256(), "provenance.csvSha256");
        if (!provenance.complaintId().matches("[1-9][0-9]*")
                || new BigInteger(provenance.complaintId()).signum() <= 0) {
            throw new IllegalArgumentException(
                    "CFPB complaintId must be a canonical positive decimal integer.");
        }
    }

    private static void validateAnalyst(CaseRecord record) {
        if (record.provenance() != null) {
            throw new IllegalArgumentException("ANALYST cases must not have provenance.");
        }
        if (record.notes() == null) {
            throw new IllegalArgumentException("ANALYST cases require analyst notes.");
        }
        requireText(record.notes().handlingNotes(), "analyst handling notes");
        requireNonNull(record.notes().outcomeAssessment(), "notes.outcomeAssessment");
    }

    private void validateReferencesExist(CaseRecord record) {
        if (record.referenceCaseIds().isEmpty()) {
            return;
        }
        for (UUID reference : record.referenceCaseIds()) {
            try {
                database.jdbcTemplate().queryForObject(
                        "SELECT source FROM cases WHERE id = ?", String.class, reference.toString());
            } catch (EmptyResultDataAccessException failure) {
                throw new IllegalArgumentException(
                        "Reference case does not exist: " + reference, failure);
            }
        }
    }

    private void requireBatch(UUID batchId) {
        Long count = database.jdbcTemplate().queryForObject(
                "SELECT COUNT(*) FROM import_batches WHERE id = ?", Long.class, batchId.toString());
        if (count == null || count == 0) {
            throw new IllegalArgumentException("Import batch does not exist: " + batchId);
        }
    }

    private void insertCase(CaseRecord record) {
        CaseRecord.Provenance provenance = record.provenance();
        database.jdbcTemplate().update(
                """
                INSERT INTO cases (
                    id, source, complaint_id, batch_id, received_date, product, sub_product,
                    issue, sub_issue, narrative, company, company_response, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                record.id().toString(),
                record.source().name(),
                provenance == null ? null : provenance.complaintId(),
                provenance == null ? null : provenance.batchId().toString(),
                date(record.receivedDate()),
                record.categories().product(),
                record.categories().subProduct(),
                record.categories().issue(),
                record.categories().subIssue(),
                record.narrative(),
                record.company(),
                record.companyResponse(),
                Timestamps.format(record.createdAt()),
                Timestamps.format(record.updatedAt()));
    }

    private void insertNotes(CaseRecord record) {
        if (record.notes() == null) {
            return;
        }
        database.jdbcTemplate().update(
                """
                INSERT INTO analyst_notes (
                    case_id, handling_notes, outcome_assessment, evaluation_notes
                ) VALUES (?, ?, ?, ?)
                """,
                record.id().toString(),
                record.notes().handlingNotes(),
                record.notes().outcomeAssessment().name(),
                record.notes().evaluationNotes());
    }

    private void insertReferences(CaseRecord record) {
        List<UUID> references = record.referenceCaseIds();
        for (int position = 0; position < references.size(); position++) {
            database.jdbcTemplate().update(
                    """
                    INSERT INTO case_references (case_id, referenced_case_id, position)
                    VALUES (?, ?, ?)
                    """,
                    record.id().toString(),
                    references.get(position).toString(),
                    position);
        }
    }

    private List<CaseRecord> attachReferences(List<CaseRecord> records) {
        if (records.isEmpty()) {
            return records;
        }
        List<UUID> ids = records.stream().map(CaseRecord::id).toList();
        String placeholders = String.join(",", ids.stream().map(ignored -> "?").toList());
        Map<UUID, List<UUID>> references = new LinkedHashMap<>();
        database.jdbcTemplate().query(
                """
                SELECT case_id, referenced_case_id
                FROM case_references
                WHERE case_id IN (%s)
                ORDER BY case_id ASC, position ASC
                """.formatted(placeholders),
                (RowCallbackHandler) resultSet -> {
                    UUID caseId = UUID.fromString(resultSet.getString("case_id"));
                    references
                            .computeIfAbsent(caseId, ignored -> new ArrayList<>())
                            .add(UUID.fromString(resultSet.getString("referenced_case_id")));
                },
                ids.toArray());

        List<CaseRecord> materialized = new ArrayList<>(records.size());
        for (CaseRecord record : records) {
            materialized.add(withReferences(
                    record, references.getOrDefault(record.id(), List.of())));
        }
        return List.copyOf(materialized);
    }

    private static CaseRecord withReferences(CaseRecord record, List<UUID> references) {
        return new CaseRecord(
                record.id(),
                record.source(),
                record.categories(),
                record.narrative(),
                record.receivedDate(),
                record.company(),
                record.companyResponse(),
                record.createdAt(),
                record.updatedAt(),
                record.provenance(),
                record.notes(),
                references);
    }

    private CaseRecord mapCase(ResultSet resultSet, int rowNumber) throws SQLException {
        CaseRecord.Source source = CaseRecord.Source.valueOf(resultSet.getString("source"));
        String batchId = resultSet.getString("batch_id");
        CaseRecord.Provenance provenance = null;
        if (batchId != null) {
            if (source != CaseRecord.Source.CFPB) {
                throw new SQLException("Non-CFPB case has a batch reference");
            }
            provenance = new CaseRecord.Provenance(
                    UUID.fromString(batchId),
                    resultSet.getString("complaint_id"),
                    resultSet.getString("archive_url"),
                    resultSet.getString("source_file_name"),
                    date(resultSet.getString("period_start")),
                    date(resultSet.getString("period_end")),
                    resultSet.getString("csv_sha256"));
        }

        CaseRecord.Notes notes = null;
        String handlingNotes = resultSet.getString("handling_notes");
        if (handlingNotes != null) {
            notes = new CaseRecord.Notes(
                    handlingNotes,
                    CaseRecord.Outcome.valueOf(resultSet.getString("outcome_assessment")),
                    resultSet.getString("evaluation_notes"));
        }

        return new CaseRecord(
                UUID.fromString(resultSet.getString("id")),
                source,
                new CategoryValues(
                        resultSet.getString("product"),
                        resultSet.getString("sub_product"),
                        resultSet.getString("issue"),
                        resultSet.getString("sub_issue")),
                resultSet.getString("narrative"),
                date(resultSet.getString("received_date")),
                resultSet.getString("company"),
                resultSet.getString("company_response"),
                Timestamps.parse(resultSet.getString("created_at")),
                Timestamps.parse(resultSet.getString("updated_at")),
                provenance,
                notes,
                List.of());
    }

    private static String date(LocalDate value) {
        return value == null ? null : value.toString();
    }

    private static LocalDate date(String value) {
        return value == null ? null : LocalDate.parse(value);
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required.");
        }
    }

    private static void requireNonNull(Object value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + " is required.");
        }
    }

    public record Page(List<CaseRecord> items, int page, int size, long totalElements) {

        public Page {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }
}
