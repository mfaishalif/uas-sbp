package id.ac.finresolve.evaluation;

import id.ac.finresolve.categories.CategoryValues;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.commons.csv.DuplicateHeaderMode;

/** Loads the frozen v2 query set and rejects malformed or duplicated query IDs. */
public final class EvaluationQueries {

    static final List<String> HEADERS = List.of(
            "queryId", "kind", "groupId", "cohort", "product", "subProduct", "issue",
            "subIssue", "narrative", "scenario");

    private EvaluationQueries() {
    }

    public static List<EvaluationQuery> load(Path path) {
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8);
                CSVParser parser = CSVFormat.DEFAULT.builder()
                        .setHeader()
                        .setSkipHeaderRecord(true)
                        .setDuplicateHeaderMode(DuplicateHeaderMode.DISALLOW)
                        .setAllowMissingColumnNames(false)
                        .setIgnoreEmptyLines(false)
                        .build()
                        .parse(reader)) {
            Map<String, Integer> headers = parser.getHeaderMap();
            for (String header : HEADERS) {
                if (!headers.containsKey(header)) {
                    throw new IllegalArgumentException(
                            "Query CSV is missing column: " + header);
                }
            }
            int expectedColumns = headers.size();
            for (String header : HEADERS) {
                if (headers.get(header) >= expectedColumns) {
                    throw new IllegalArgumentException("Query CSV has an invalid header row");
                }
            }

            Map<String, EvaluationQuery> queries = new LinkedHashMap<>();
            for (CSVRecord record : parser) {
                if (record.size() != expectedColumns) {
                    throw new IllegalArgumentException(
                            "Query CSV record " + record.getRecordNumber()
                                    + " has " + record.size() + " columns, expected "
                                    + expectedColumns);
                }
                String queryId = required(record, "queryId");
                EvaluationQuery query = new EvaluationQuery(
                        queryId,
                        EvaluationQuery.Kind.parse(required(record, "kind")),
                        required(record, "groupId"),
                        EvaluationQuery.Cohort.parse(required(record, "cohort")),
                        new CategoryValues(
                                required(record, "product"),
                                nullable(record, "subProduct"),
                                required(record, "issue"),
                                nullable(record, "subIssue")),
                        required(record, "narrative"),
                        required(record, "scenario"));
                if (queries.putIfAbsent(queryId, query) != null) {
                    throw new IllegalArgumentException(
                            "Duplicate query ID in query CSV: " + queryId);
                }
            }
            if (queries.isEmpty()) {
                throw new IllegalArgumentException("Query CSV contains no queries");
            }
            validateGroups(queries.values().stream().toList());
            return List.copyOf(queries.values());
        } catch (IOException failure) {
            throw new IllegalArgumentException("Cannot read query CSV: " + path, failure);
        }
    }

    /** Groups must be homogeneous so paraphrases cannot leak across kinds or cohorts. */
    public static void validateGroups(List<EvaluationQuery> queries) {
        Map<String, EvaluationQuery> firstInGroup = new LinkedHashMap<>();
        Map<String, Integer> groupSizes = new LinkedHashMap<>();
        for (EvaluationQuery query : queries) {
            EvaluationQuery first = firstInGroup.putIfAbsent(query.groupId(), query);
            if (first != null) {
                if (first.kind() != query.kind()) {
                    throw new IllegalArgumentException(
                            "Group " + query.groupId() + " mixes query kinds");
                }
                if (first.cohort() != query.cohort()) {
                    throw new IllegalArgumentException(
                            "Group " + query.groupId() + " mixes cohorts");
                }
            }
            groupSizes.merge(query.groupId(), 1, Integer::sum);
        }
        for (Map.Entry<String, Integer> entry : groupSizes.entrySet()) {
            EvaluationQuery query = firstInGroup.get(entry.getKey());
            if (query.cohort() == EvaluationQuery.Cohort.STUDY
                    && entry.getValue() != 2) {
                throw new IllegalArgumentException(
                        "Study group " + entry.getKey() + " must contain exactly two variants");
            }
            if (query.cohort() == EvaluationQuery.Cohort.DEVELOPMENT
                    && entry.getValue() != 1) {
                throw new IllegalArgumentException(
                        "Development group " + entry.getKey() + " must contain one query");
            }
        }
    }

    static String required(CSVRecord record, String header) {
        String value = nullable(record, header);
        if (value == null) {
            throw new IllegalArgumentException(
                    "Missing " + header + " in CSV record " + record.getRecordNumber());
        }
        return value;
    }

    static String nullable(CSVRecord record, String header) {
        if (!record.isMapped(header) || !record.isSet(header)) {
            return null;
        }
        String value = record.get(header);
        return value == null || value.isBlank() ? null : value.strip();
    }
}
