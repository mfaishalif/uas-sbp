package id.ac.finresolve.evaluation;

import id.ac.finresolve.cases.CaseRecord;
import id.ac.finresolve.categories.CategoryValues;
import id.ac.finresolve.similarity.SimilarityResult;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;
import org.apache.commons.csv.DuplicateHeaderMode;

/**
 * Blinded union of top-5 candidates across every calibration configuration.
 *
 * <p>The exported file intentionally contains no score, rank, or configuration identity. Each
 * query/case pair appears once so it can be judged once.
 */
public final class EvaluationCandidates {

    static final List<String> HEADERS = List.of(
            "queryId", "kind", "scenario", "queryNarrative", "cfpbId",
            "product", "subProduct", "issue", "subIssue", "caseNarrative");

    private EvaluationCandidates() {
    }

    public static List<EvaluationCandidate> pool(
            EvaluationRunner runner, List<EvaluationQuery> queries) {
        Map<String, CaseRecord> byComplaintId = new HashMap<>();
        runner.corpus().cases().forEach(record -> byComplaintId.put(
                EvaluationCaseIds.complaintId(record), record));

        List<EvaluationCandidate> candidates = new ArrayList<>();
        for (EvaluationQuery query : queries) {
            Set<String> ids = new LinkedHashSet<>();
            for (EvaluationConfig config : EvaluationConfig.grid()) {
                List<SimilarityResult> results = runner.rank(query, config);
                for (SimilarityResult result : results) {
                    ids.add(EvaluationCaseIds.complaintId(result.caseRecord()));
                }
            }
            List<String> sortedIds = new ArrayList<>(ids);
            sortedIds.sort(Comparator.naturalOrder());
            for (String cfpbId : sortedIds) {
                CaseRecord record = byComplaintId.get(cfpbId);
                if (record == null) {
                    throw new IllegalStateException("Unknown corpus case: " + cfpbId);
                }
                CategoryValues categories = record.categories();
                candidates.add(new EvaluationCandidate(
                        query.queryId(),
                        query.kind(),
                        query.scenario(),
                        query.narrative(),
                        cfpbId,
                        categories.product(),
                        categories.subProduct(),
                        categories.issue(),
                        categories.subIssue(),
                        record.narrative()));
            }
        }
        candidates.sort(Comparator
                .comparing(EvaluationCandidate::queryId)
                .thenComparing(EvaluationCandidate::cfpbId));
        return List.copyOf(candidates);
    }

    public static void write(Path path, List<EvaluationCandidate> candidates) {
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
                            candidate.queryId(),
                            candidate.kind().csvValue(),
                            candidate.scenario(),
                            candidate.queryNarrative(),
                            candidate.cfpbId(),
                            candidate.product(),
                            candidate.subProduct(),
                            candidate.issue(),
                            candidate.subIssue(),
                            candidate.caseNarrative());
                }
            }
        } catch (IOException failure) {
            throw new IllegalArgumentException("Cannot write candidates: " + path, failure);
        }
    }

    public static List<EvaluationLabels.Pair> readPairs(
            Path path,
            List<EvaluationQuery> queries,
            EvaluationCorpus corpus) {
        return read(path, queries, corpus).stream()
                .map(candidate -> new EvaluationLabels.Pair(
                        candidate.queryId(), candidate.cfpbId()))
                .toList();
    }

    public static List<EvaluationCandidate> read(
            Path path,
            List<EvaluationQuery> queries,
            EvaluationCorpus corpus) {
        Map<String, EvaluationQuery> queriesById = new LinkedHashMap<>();
        queries.forEach(query -> queriesById.put(query.queryId(), query));
        Map<String, CaseRecord> casesByComplaintId = new HashMap<>();
        corpus.cases().forEach(record -> casesByComplaintId.put(
                EvaluationCaseIds.complaintId(record), record));

        Map<EvaluationLabels.Pair, EvaluationCandidate> candidates = new LinkedHashMap<>();
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
                String cfpbId = EvaluationQueries.required(record, "cfpbId");
                EvaluationQuery query = queriesById.get(queryId);
                if (query == null) {
                    throw new IllegalArgumentException(
                            "Candidate file references unknown query: " + queryId);
                }
                CaseRecord caseRecord = casesByComplaintId.get(cfpbId);
                if (caseRecord == null) {
                    throw new IllegalArgumentException(
                            "Candidate file references unknown CFPB case: " + cfpbId);
                }
                EvaluationLabels.Pair pair = new EvaluationLabels.Pair(queryId, cfpbId);
                EvaluationCandidate candidate = new EvaluationCandidate(
                        queryId,
                        query.kind(),
                        EvaluationQueries.nullable(record, "scenario"),
                        EvaluationQueries.required(record, "queryNarrative"),
                        cfpbId,
                        caseRecord.categories().product(),
                        caseRecord.categories().subProduct(),
                        caseRecord.categories().issue(),
                        caseRecord.categories().subIssue(),
                        caseRecord.narrative());
                if (candidates.putIfAbsent(pair, candidate) != null) {
                    throw new IllegalArgumentException(
                            "Duplicate candidate pair: " + queryId + "/" + cfpbId);
                }
            }
        } catch (IOException failure) {
            throw new IllegalArgumentException("Cannot read candidates: " + path, failure);
        }
        if (candidates.isEmpty()) {
            throw new IllegalArgumentException("Candidate file contains no pairs");
        }
        return List.copyOf(candidates.values());
    }

    public record EvaluationCandidate(
            String queryId,
            EvaluationQuery.Kind kind,
            String scenario,
            String queryNarrative,
            String cfpbId,
            String product,
            String subProduct,
            String issue,
            String subIssue,
            String caseNarrative) {
    }
}
