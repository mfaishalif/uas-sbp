package id.ac.finresolve.evaluation;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;
import org.apache.commons.csv.DuplicateHeaderMode;

/** Reads and writes the committed query split. */
public final class EvaluationSplitFile {

    private static final List<String> HEADERS = List.of("queryId", "kind", "split");

    private EvaluationSplitFile() {
    }

    public static void write(
            Path path, List<EvaluationQuery> queries, EvaluationSplit split) {
        try {
            Files.createDirectories(path.toAbsolutePath().getParent());
            try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8);
                    CSVPrinter printer = new CSVPrinter(
                            writer,
                            CSVFormat.DEFAULT.builder()
                                    .setHeader(HEADERS.toArray(String[]::new))
                                    .setRecordSeparator("\n")
                                    .build())) {
                queries.stream()
                        .sorted(java.util.Comparator.comparing(EvaluationQuery::queryId))
                        .forEach(query -> {
                            try {
                                printer.printRecord(
                                        query.queryId(),
                                        query.kind().csvValue(),
                                        split.partOf(query.queryId()).csvValue());
                            } catch (IOException failure) {
                                throw new IllegalStateException(failure);
                            }
                        });
            }
        } catch (IOException failure) {
            throw new IllegalArgumentException("Cannot write split file: " + path, failure);
        }
    }

    /**
     * Reads the split and checks it against the deterministic group rule. A committed split that
     * differs from the generator would make calibration and test results irreproducible.
     */
    public static EvaluationSplit verify(
            Path path, List<EvaluationQuery> queries, EvaluationSplit expected) {
        Map<String, EvaluationSplit.Part> actual = new LinkedHashMap<>();
        Map<String, String> kinds = new LinkedHashMap<>();
        try (CSVParser parser = CSVParser.parse(
                Files.newBufferedReader(path, StandardCharsets.UTF_8),
                CSVFormat.DEFAULT.builder()
                        .setHeader()
                        .setSkipHeaderRecord(true)
                        .setDuplicateHeaderMode(DuplicateHeaderMode.DISALLOW)
                        .setAllowMissingColumnNames(false)
                        .build())) {
            for (CSVRecord record : parser) {
                String queryId = EvaluationQueries.required(record, "queryId");
                String kind = EvaluationQueries.required(record, "kind");
                EvaluationSplit.Part part = EvaluationSplit.Part.parse(
                        EvaluationQueries.required(record, "split"));
                if (actual.putIfAbsent(queryId, part) != null) {
                    throw new IllegalArgumentException(
                            "Duplicate query in split file: " + queryId);
                }
                kinds.put(queryId, kind);
            }
        } catch (IOException failure) {
            throw new IllegalArgumentException("Cannot read split file: " + path, failure);
        }

        if (!actual.keySet().equals(expected.asMap().keySet())) {
            throw new IllegalArgumentException(
                    "Split file does not cover exactly the frozen queries");
        }
        Map<String, EvaluationSplit.Part> groupParts = new LinkedHashMap<>();
        for (EvaluationQuery query : queries) {
            if (!kinds.get(query.queryId()).equals(query.kind().csvValue())) {
                throw new IllegalArgumentException(
                        "Split file kind does not match query " + query.queryId());
            }
            if (actual.get(query.queryId()) != expected.partOf(query.queryId())) {
                throw new IllegalArgumentException(
                        "Split file disagrees with the grouped seed for " + query.queryId());
            }
            EvaluationSplit.Part existing = groupParts.putIfAbsent(
                    query.groupId(), actual.get(query.queryId()));
            if (existing != null && existing != actual.get(query.queryId())) {
                throw new IllegalArgumentException(
                        "Group " + query.groupId() + " leaks across split parts");
            }
        }
        return expected;
    }
}
