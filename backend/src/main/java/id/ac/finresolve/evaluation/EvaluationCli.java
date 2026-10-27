package id.ac.finresolve.evaluation;

import id.ac.finresolve.similarity.SimilarityResult;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Offline evaluation command-line entry point.
 *
 * <p>Run through the evaluation Maven profile, for example:
 * {@code ./mvnw -q -Pevaluation exec:java -Dexec.args="export-candidates"}.
 */
public final class EvaluationCli {

    private static final Path SAMPLE = Path.of("../data/cfpb/sample.csv");
    private static final Path MANIFEST = Path.of("../data/cfpb/manifest.json");
    private static final Path QUERIES = Path.of("../docs/evaluation/queries.csv");
    private static final Path SPLIT = Path.of("../docs/evaluation/split.csv");
    private static final Path CANDIDATES = Path.of("../docs/evaluation/candidates.csv");
    private static final Path LABELS = Path.of("../docs/evaluation/exploratory-labels.csv");
    private static final Path OUT_DIR = Path.of("../docs/evaluation/results");
    private static final Path SUMMARY = OUT_DIR.resolve("summary.json");

    private EvaluationCli() {
    }

    public static void main(String[] args) {
        try {
            run(args);
        } catch (RuntimeException failure) {
            String message = failure.getMessage();
            System.err.println("evaluation: "
                    + (message == null || message.isBlank()
                            ? failure.getClass().getSimpleName()
                            : message));
            System.exit(1);
        }
    }

    private static void run(String[] args) {
        if (args == null || args.length == 0 || "help".equals(args[0])) {
            usage();
            return;
        }
        Options options = Options.parse(args);
        String command = args[0];
        switch (command) {
            case "write-split" -> writeSplit(options);
            case "export-candidates" -> exportCandidates(options);
            case "validate-labels" -> validateLabels(options);
            case "evaluate" -> evaluate(options);
            case "compare" -> compare(options);
            default -> throw new IllegalArgumentException("Unknown command: " + command);
        }
    }

    private static void writeSplit(Options options) {
        List<EvaluationQuery> queries = EvaluationQueries.load(options.path("queries", QUERIES));
        EvaluationSplit split = EvaluationSplit.create(queries);
        Path path = options.path("split", SPLIT);
        EvaluationSplitFile.write(path, queries, split);
        System.out.println("Wrote split to " + path.toAbsolutePath().normalize()
                + " (calibration=" + split.count(EvaluationSplit.Part.CALIBRATION)
                + ", test=" + split.count(EvaluationSplit.Part.TEST) + ")");
    }

    private static void exportCandidates(Options options) {
        List<EvaluationQuery> queries = EvaluationQueries.load(options.path("queries", QUERIES));
        EvaluationSplit expected = EvaluationSplit.create(queries);
        EvaluationSplitFile.verify(options.path("split", SPLIT), queries, expected);
        EvaluationCorpus corpus = EvaluationCorpus.load(
                options.path("sample", SAMPLE), options.path("manifest", MANIFEST));
        EvaluationRunner runner = new EvaluationRunner(corpus);
        List<EvaluationCandidates.EvaluationCandidate> candidates =
                EvaluationCandidates.pool(runner, queries);
        Path path = options.path("candidates", CANDIDATES);
        EvaluationCandidates.write(path, candidates);
        System.out.println("Wrote " + candidates.size() + " blinded query/case pairs to "
                + path.toAbsolutePath().normalize()
                + " from " + corpus.sampleRecordCount() + " cases ("
                + corpus.sampleSha256() + ")");
    }

    private static void compare(Options options) {
        try {
            System.out.println(EvaluationReportWriter.compareSummary(
                    options.path("summary", SUMMARY)));
        } catch (java.io.IOException failure) {
            throw new IllegalArgumentException("Cannot read evaluation summary", failure);
        }
    }

    private static void validateLabels(Options options) {        List<EvaluationQuery> queries = EvaluationQueries.load(options.path("queries", QUERIES));
        EvaluationCorpus corpus = EvaluationCorpus.load(
                options.path("sample", SAMPLE), options.path("manifest", MANIFEST));
        List<EvaluationLabels.Pair> pairs = EvaluationCandidates.readPairs(
                options.path("candidates", CANDIDATES), queries, corpus);
        EvaluationLabels labels = EvaluationLabelsFile.read(
                options.path("labels", LABELS), queries, corpus);
        labels.requireCoverage(pairs);
        System.out.println("Labels valid: " + labels.size()
                + " judgments cover " + pairs.size() + " pooled pairs");
    }

    private static void evaluate(Options options) {
        List<EvaluationQuery> queries = EvaluationQueries.load(options.path("queries", QUERIES));
        EvaluationSplit split = EvaluationSplitFile.verify(
                options.path("split", SPLIT), queries, EvaluationSplit.create(queries));
        EvaluationCorpus corpus = EvaluationCorpus.load(
                options.path("sample", SAMPLE), options.path("manifest", MANIFEST));
        List<EvaluationLabels.Pair> pairs = EvaluationCandidates.readPairs(
                options.path("candidates", CANDIDATES), queries, corpus);
        EvaluationLabels labels = EvaluationLabelsFile.read(
                options.path("labels", LABELS), queries, corpus);
        labels.requireCoverage(pairs);

        EvaluationRunner runner = new EvaluationRunner(corpus);
        List<EvaluationQuery> calibrationQueries =
                split.queriesIn(queries, EvaluationSplit.Part.CALIBRATION);
        List<EvaluationQuery> testQueries =
                split.queriesIn(queries, EvaluationSplit.Part.TEST);
        List<EvaluationConfig> grid = EvaluationConfig.grid();
        Map<EvaluationConfig, Map<String, List<SimilarityResult>>> calibrationResults =
                runner.rankAll(calibrationQueries, grid);
        Map<EvaluationConfig, EvaluationMetrics.Aggregate> calibrationAggregates =
                new LinkedHashMap<>();
        for (EvaluationConfig config : grid) {
            calibrationAggregates.put(
                    config,
                    EvaluationMetrics.aggregate(
                            calibrationQueries, calibrationResults.get(config), labels));
        }
        EvaluationConfig baseline = EvaluationConfig.baseline();
        EvaluationConfig selected = EvaluationSelection.select(
                grid, baseline, calibrationAggregates);

        Map<String, List<SimilarityResult>> calibrationBaselineResults =
                calibrationResults.get(baseline);
        Map<String, List<SimilarityResult>> calibrationSelectedResults =
                calibrationResults.get(selected);
        Map<String, List<SimilarityResult>> testBaselineResults =
                runner.rank(testQueries, baseline);
        Map<String, List<SimilarityResult>> testSelectedResults =
                selected.sameAs(baseline)
                        ? testBaselineResults
                        : runner.rank(testQueries, selected);

        EvaluationStudy study = new EvaluationStudy(
                queries,
                split,
                baseline,
                selected,
                calibrationAggregates,
                calibrationBaselineResults,
                calibrationSelectedResults,
                testBaselineResults,
                testSelectedResults,
                labels,
                EvaluationCandidates.read(
                        options.path("candidates", CANDIDATES), queries, corpus),
                corpus.sampleSha256(),
                corpus.sampleRecordCount());
        Path outputDirectory = options.path("out-dir", OUT_DIR);
        EvaluationReportWriter.write(outputDirectory, study);

        EvaluationMetrics.Aggregate testBaselineAggregate = EvaluationMetrics.aggregate(
                testQueries, testBaselineResults, labels);
        EvaluationMetrics.Aggregate testSelectedAggregate = EvaluationMetrics.aggregate(
                testQueries, testSelectedResults, labels);
        EvaluationSelection.Gate gate =
                EvaluationSelection.gate(testBaselineAggregate, testSelectedAggregate);
        System.out.printf(
                Locale.ROOT,
                "Baseline %s: cal P@5=%.3f, test P@5=%.3f, negative failures=%d%n",
                baseline.label(),
                calibrationAggregates.get(baseline).meanPrecisionAt5(),
                testBaselineAggregate.meanPrecisionAt5(),
                testBaselineAggregate.negativeFailures());
        System.out.printf(
                Locale.ROOT,
                "Selected %s: cal P@5=%.3f, test P@5=%.3f, negative failures=%d%n",
                selected.label(),
                calibrationAggregates.get(selected).meanPrecisionAt5(),
                testSelectedAggregate.meanPrecisionAt5(),
                testSelectedAggregate.negativeFailures());
        System.out.println("Decision: " + (gate.passed()
                ? "APPLY_SELECTED"
                : "KEEP_BASELINE"));
        System.out.println("Artifacts: " + outputDirectory.toAbsolutePath().normalize());
    }

    private static void usage() {
        System.out.println("""
                Usage: evaluation <command> [--option value]

                Commands:
                  write-split         write the deterministic stratified split
                  export-candidates   export the blinded union of top-5 candidates
                  validate-labels     fail on missing, duplicate, or invalid judgments
                  evaluate            compute calibration metrics, select, test, and write results
                  compare             print the stored baseline/selected comparison

                Options:
                  --sample <path>       CFPB sample CSV (default ../data/cfpb/sample.csv)
                  --manifest <path>     sample manifest (default ../data/cfpb/manifest.json)
                  --queries <path>      frozen queries (default ../docs/evaluation/queries.csv)
                  --split <path>        split CSV (default ../docs/evaluation/split.csv)
                  --candidates <path>   candidate pool CSV
                  --labels <path>       exploratory judgments CSV
                  --out-dir <path>      result directory (default ../docs/evaluation/results)
                  --summary <path>      summary JSON for compare
                """);
    }

    private record Options(Map<String, String> values) {

        static Options parse(String[] args) {
            Map<String, String> values = new LinkedHashMap<>();
            for (int index = 1; index < args.length; index++) {
                String key = args[index];
                if (!key.startsWith("--")) {
                    throw new IllegalArgumentException("Unexpected argument: " + key);
                }
                if (index + 1 >= args.length) {
                    throw new IllegalArgumentException("Missing value for " + key);
                }
                values.put(key.substring(2), args[++index]);
            }
            return new Options(Map.copyOf(values));
        }

        Path path(String name, Path defaultValue) {
            String value = values.get(name);
            return value == null ? defaultValue : Path.of(value);
        }
    }
}
