package id.ac.finresolve.evaluation;

import id.ac.finresolve.evaluation.EvaluationCandidates.EvaluationCandidate;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;
import org.apache.commons.csv.DuplicateHeaderMode;

/** CSV transport for exploratory judgments. */
public final class EvaluationLabelsFile {

    static final List<String> HEADERS =
            List.of("queryId", "cfpbId", "relevant", "reason");

    private EvaluationLabelsFile() {
    }

    public static EvaluationLabels read(
            Path path,
            List<EvaluationQuery> queries,
            EvaluationCorpus corpus) {
        Set<String> queryIds = new HashSet<>();
        queries.forEach(query -> queryIds.add(query.queryId()));
        Set<String> cfpbIds = new HashSet<>();
        corpus.cases().forEach(
                record -> cfpbIds.add(EvaluationCaseIds.complaintId(record)));

        List<EvaluationLabels.Judgment> rows = new ArrayList<>();
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8);
                CSVParser parser = CSVFormat.DEFAULT.builder()
                        .setHeader()
                        .setSkipHeaderRecord(true)
                        .setDuplicateHeaderMode(DuplicateHeaderMode.DISALLOW)
                        .setAllowMissingColumnNames(false)
                        .build()
                        .parse(reader)) {
            for (String header : HEADERS) {
                if (!parser.getHeaderMap().containsKey(header)) {
                    throw new IllegalArgumentException(
                            "Label CSV is missing column: " + header);
                }
            }
            for (CSVRecord record : parser) {
                String queryId = EvaluationQueries.required(record, "queryId");
                String cfpbId = EvaluationQueries.required(record, "cfpbId");
                String relevant = EvaluationQueries.required(record, "relevant");
                String reason = EvaluationQueries.required(record, "reason");
                if (!queryIds.contains(queryId)) {
                    throw new IllegalArgumentException(
                            "Label references unknown query: " + queryId);
                }
                if (!cfpbIds.contains(cfpbId)) {
                    throw new IllegalArgumentException(
                            "Label references unknown CFPB case: " + cfpbId);
                }
                boolean value = switch (relevant) {
                    case "0" -> false;
                    case "1" -> true;
                    default -> throw new IllegalArgumentException(
                            "relevant must be 0 or 1 for " + queryId + "/" + cfpbId);
                };
                rows.add(new EvaluationLabels.Judgment(queryId, cfpbId, value, reason));
            }
        } catch (IOException failure) {
            throw new IllegalArgumentException("Cannot read label CSV: " + path, failure);
        }
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("Label CSV contains no judgments");
        }
        return EvaluationLabels.of(rows);
    }

    public static void writeTemplate(Path path, List<EvaluationCandidate> candidates) {
        try {
            Files.createDirectories(path.toAbsolutePath().getParent());
            try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8);
                    CSVPrinter printer = new CSVPrinter(
                            writer,
                            CSVFormat.DEFAULT.builder()
                                    .setHeader(HEADERS.toArray(String[]::new))
                                    .setRecordSeparator("\n")
                                    .build())) {
                for (EvaluationCandidate candidate : candidates) {
                    printer.printRecord(
                            candidate.queryId(), candidate.cfpbId(), "", "");
                }
            }
        } catch (IOException failure) {
            throw new IllegalArgumentException("Cannot write label template: " + path, failure);
        }
    }
}
