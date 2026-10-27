package id.ac.finresolve.evaluation;

import id.ac.finresolve.domain.cases.CaseRecord;
import id.ac.finresolve.evaluation.EvaluationLabels.Pair;
import id.ac.finresolve.domain.similarity.SimilarityResult;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
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
 * Blinded union of top-5 candidates across calibration configurations.
 *
 * <p>The committed candidate index stores only query/case identity. Review packets contain pair
 * identity, both narratives, and both category tuples; they intentionally omit kind, scenario,
 * scores, ranks, configuration identity, split, and archived labels.
 */
public final class EvaluationCandidates {

    static final List<String> HEADERS = List.of("queryId", "cfpbId");
    static final List<String> PACKET_HEADERS = List.of(
            "queryId", "cfpbId", "queryNarrative",
            "queryProduct", "querySubProduct", "queryIssue", "querySubIssue",
            "caseNarrative", "caseProduct", "caseSubProduct", "caseIssue", "caseSubIssue");

    private EvaluationCandidates() {
    }

    public static List<Pair> pool(
            EvaluationRunner runner,
            List<EvaluationQuery> queries,
            List<EvaluationConfig> configs) {
        List<Pair> candidates = new ArrayList<>();
        for (EvaluationQuery query : queries) {
            Set<String> ids = new LinkedHashSet<>();
            for (EvaluationConfig config : configs) {
                List<SimilarityResult> results = runner.rank(query, config);
                for (SimilarityResult result : results) {
                    ids.add(EvaluationCaseIds.complaintId(result.caseRecord()));
                }
            }
            List<String> sortedIds = new ArrayList<>(ids);
            sortedIds.sort(Comparator.naturalOrder());
            for (String cfpbId : sortedIds) {
                candidates.add(new Pair(query.queryId(), cfpbId));
            }
        }
        candidates.sort(Comparator
                .comparing(Pair::queryId)
                .thenComparing(Pair::cfpbId));
        return List.copyOf(candidates);
    }

    public static void write(Path path, List<Pair> candidates) {
        try {
            Files.createDirectories(path.toAbsolutePath().getParent());
            try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8);
                    CSVPrinter printer = new CSVPrinter(
                            writer,
                            CSVFormat.DEFAULT.builder()
                                    .setHeader(HEADERS.toArray(String[]::new))
                                    .setRecordSeparator("\n")
                                    .build())) {
                for (Pair candidate : candidates) {
                    printer.printRecord(candidate.queryId(), candidate.cfpbId());
                }
            }
        } catch (IOException failure) {
            throw new IllegalArgumentException("Cannot write candidates: " + path, failure);
        }
    }

    public static List<Pair> readPairs(
            Path path,
            List<EvaluationQuery> queries,
            EvaluationCorpus corpus) {
        Set<String> queryIds = queries.stream()
                .map(EvaluationQuery::queryId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        Set<String> cfpbIds = corpus.cases().stream()
                .map(EvaluationCaseIds::complaintId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());

        List<Pair> candidates = new ArrayList<>();
        Set<Pair> seen = new LinkedHashSet<>();
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
                if (!queryIds.contains(queryId)) {
                    throw new IllegalArgumentException(
                            "Candidate file references unknown query: " + queryId);
                }
                if (!cfpbIds.contains(cfpbId)) {
                    throw new IllegalArgumentException(
                            "Candidate file references unknown CFPB case: " + cfpbId);
                }
                Pair pair = new Pair(queryId, cfpbId);
                if (!seen.add(pair)) {
                    throw new IllegalArgumentException(
                            "Duplicate candidate pair: " + queryId + "/" + cfpbId);
                }
                candidates.add(pair);
            }
        } catch (IOException failure) {
            throw new IllegalArgumentException("Cannot read candidates: " + path, failure);
        }
        if (candidates.isEmpty()) {
            throw new IllegalArgumentException("Candidate file contains no pairs");
        }
        return List.copyOf(candidates);
    }

    /**
     * Writes deterministic blind packets and returns the combined SHA-256 of their contents.
     */
    public static String writePackets(
            Path directory,
            List<EvaluationQuery> queries,
            EvaluationCorpus corpus,
            List<Pair> candidates,
            int batchSize) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("Packet batch size must be positive");
        }
        Map<String, EvaluationQuery> queriesById = new LinkedHashMap<>();
        queries.forEach(query -> queriesById.put(query.queryId(), query));
        Map<String, CaseRecord> casesById = new HashMap<>();
        corpus.cases().forEach(
                record -> casesById.put(EvaluationCaseIds.complaintId(record), record));

        List<Pair> sorted = candidates.stream()
                .sorted(Comparator.comparing(Pair::queryId).thenComparing(Pair::cfpbId))
                .toList();
        try {
            Files.createDirectories(directory);
            try (var existing = Files.list(directory)) {
                for (Path old : existing.toList()) {
                    if (old.getFileName().toString().startsWith("batch-")) {
                        Files.delete(old);
                    }
                }
            }

            MessageDigest combined = MessageDigest.getInstance("SHA-256");
            int batch = 0;
            for (int start = 0; start < sorted.size(); start += batchSize) {
                batch++;
                Path file = directory.resolve("batch-%03d.csv".formatted(batch));
                int end = Math.min(start + batchSize, sorted.size());
                try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8);
                        CSVPrinter printer = new CSVPrinter(
                                writer,
                                CSVFormat.DEFAULT.builder()
                                        .setHeader(PACKET_HEADERS.toArray(String[]::new))
                                        .setRecordSeparator("\n")
                                        .build())) {
                    for (Pair pair : sorted.subList(start, end)) {
                        EvaluationQuery query = queriesById.get(pair.queryId());
                        CaseRecord record = casesById.get(pair.cfpbId());
                        if (query == null || record == null) {
                            throw new IllegalArgumentException(
                                    "Packet pair is not part of the study: " + pair.queryId()
                                            + "/" + pair.cfpbId());
                        }
                        printer.printRecord(
                                pair.queryId(),
                                pair.cfpbId(),
                                query.narrative(),
                                query.categories().product(),
                                query.categories().subProduct(),
                                query.categories().issue(),
                                query.categories().subIssue(),
                                record.narrative(),
                                record.categories().product(),
                                record.categories().subProduct(),
                                record.categories().issue(),
                                record.categories().subIssue());
                    }
                }
                byte[] bytes = Files.readAllBytes(file);
                combined.update(file.getFileName().toString().getBytes(StandardCharsets.UTF_8));
                combined.update((byte) 0);
                combined.update(bytes);
            }
            return HexFormat.of().formatHex(combined.digest());
        } catch (IOException | NoSuchAlgorithmException failure) {
            throw new IllegalArgumentException(
                    "Cannot write blind review packets: " + directory, failure);
        }
    }
}
