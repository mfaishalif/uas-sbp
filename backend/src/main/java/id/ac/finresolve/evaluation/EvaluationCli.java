package id.ac.finresolve.evaluation;

import id.ac.finresolve.domain.similarity.SimilarityMode;
import id.ac.finresolve.domain.similarity.SimilarityResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Offline evaluation command-line entry point.
 *
 * <p>Run through the evaluation Maven profile, for example:
 * {@code ./mvnw -q -Pevaluation exec:java -Dexec.args="select"}.
 */
public final class EvaluationCli {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Path STUDY = defaultStudy();
    private static final int PACKET_BATCH_SIZE = 25;

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
            case "select" -> select(options);
            case "validate-labels" -> validateLabels(options);
            case "evaluate" -> evaluate(options);
            case "compare" -> compare(options);
            default -> throw new IllegalArgumentException("Unknown command: " + command);
        }
    }

    private static void writeSplit(Options options) {
        EvaluationManifest manifest = manifest(options);
        List<EvaluationQuery> queries = EvaluationQueries.load(manifest.queries());
        EvaluationSplit split = EvaluationSplit.create(queries, manifest.splitSpec());
        EvaluationSplitFile.write(manifest.split(), queries, split);
        EvaluationStudyRunner study = EvaluationStudyRunner.load(manifest);
        System.out.println("Wrote split to " + manifest.split()
                + " (calibration=" + study.split().count(EvaluationSplit.Part.CALIBRATION)
                + ", test=" + study.split().count(EvaluationSplit.Part.TEST)
                + ", study groups=" + manifest.expected().studyGroups() + ")");
    }

    private static void exportCandidates(Options options) {
        EvaluationManifest manifest = manifest(options);
        EvaluationStudyRunner study = EvaluationStudyRunner.load(manifest);
        EvaluationSplit.Part part = part(options, true);
        List<EvaluationConfig> configs;
        Path candidatesPath;
        if (part == EvaluationSplit.Part.CALIBRATION) {
            configs = study.configs();
            candidatesPath = manifest.calibrationCandidates();
        } else {
            EvaluationSelectionFile.FrozenSelection frozen =
                    EvaluationSelectionFile.read(manifest.selection());
            if (!frozen.precisionImproved()
                    || !"RUN_TEST".equals(frozen.decision())) {
                throw new IllegalArgumentException(
                        "Final test is unused because calibration did not improve precision@5");
            }
            if (!frozen.selected().sameAs(manifest.baseline())
                    && !study.configs().contains(frozen.selected())) {
                throw new IllegalArgumentException(
                        "Selected configuration is outside the frozen grid");
            }
            configs = List.of(manifest.baseline(), frozen.selected());
            candidatesPath = manifest.finalCandidates();
        }

        List<EvaluationQuery> queries = study.queriesIn(part);
        List<EvaluationLabels.Pair> pairs =
                EvaluationCandidates.pool(study.runner(), queries, configs);
        EvaluationCandidates.write(candidatesPath, pairs);
        String packetHash = EvaluationCandidates.writePackets(
                manifest.packetsDirectory().resolve(part.csvValue()),
                queries,
                study.corpus(),
                pairs,
                PACKET_BATCH_SIZE);
        updatePacketChecksums(
                manifest.packetChecksums(),
                part == EvaluationSplit.Part.TEST ? "final" : "calibration",
                packetHash);
        System.out.println("Wrote " + pairs.size() + " blinded query/case pairs to "
                + candidatesPath + " and packet SHA-256 " + packetHash);
    }

    private static void select(Options options) {
        EvaluationManifest manifest = manifest(options);
        EvaluationStudyRunner study = EvaluationStudyRunner.load(manifest);
        List<EvaluationLabels.Pair> pairs = EvaluationCandidates.readPairs(
                manifest.calibrationCandidates(), study.queries(), study.corpus());
        EvaluationLabelsFile.ValidatedLabels validated = EvaluationLabelsFile.readAndAudit(
                manifest.calibrationLabels(),
                study.queries(),
                study.corpus(),
                pairs);

        Map<EvaluationConfig, Map<String, List<SimilarityResult>>> ranking =
                study.rank(EvaluationSplit.Part.CALIBRATION, study.configs());
        Map<EvaluationConfig, EvaluationMetrics.Aggregate> aggregates =
                study.aggregates(EvaluationSplit.Part.CALIBRATION, ranking, validated.labels());
        EvaluationSelection.Selection selection = EvaluationSelection.select(
                study.configs(), manifest.baseline(), aggregates, study.grid());
        EvaluationConfig bestBaseline = EvaluationSelection.bestInMode(
                study.configs(), SimilarityMode.BASELINE,
                manifest.baseline(), aggregates, study.grid());
        EvaluationConfig bestPhrases = EvaluationSelection.bestInMode(
                study.configs(), SimilarityMode.PHRASES,
                manifest.baseline(), aggregates, study.grid());

        Map<String, String> checksums = calibrationChecksums(
                manifest, study, selection.selected());
        EvaluationSelectionFile.write(
                manifest.selection(),
                manifest,
                selection,
                aggregates,
                bestBaseline,
                bestPhrases,
                checksums);
        System.out.printf(
                Locale.ROOT,
                "Calibration: baseline %s P@5=%.3f; selected %s P@5=%.3f; "
                        + "eligible=%d; decision=%s%n",
                manifest.baseline().label(),
                aggregates.get(manifest.baseline()).meanPrecisionAt5(),
                selection.selected().label(),
                aggregates.get(selection.selected()).meanPrecisionAt5(),
                selection.eligibleCandidates(),
                selection.precisionImproved() ? "RUN_TEST" : "KEEP_BASELINE");
    }

    private static void validateLabels(Options options) {
        EvaluationManifest manifest = manifest(options);
        EvaluationStudyRunner study = EvaluationStudyRunner.load(manifest);
        EvaluationSplit.Part part = part(options, false);
        if (part == null) {
            validatePart(manifest, study, EvaluationSplit.Part.CALIBRATION);
            if (Files.exists(manifest.finalCandidates())) {
                validatePart(manifest, study, EvaluationSplit.Part.TEST);
            }
            return;
        }
        validatePart(manifest, study, part);
    }

    private static void validatePart(
            EvaluationManifest manifest,
            EvaluationStudyRunner study,
            EvaluationSplit.Part part) {
        boolean calibration = part == EvaluationSplit.Part.CALIBRATION;
        List<EvaluationLabels.Pair> pairs = EvaluationCandidates.readPairs(
                calibration ? manifest.calibrationCandidates() : manifest.finalCandidates(),
                study.queries(),
                study.corpus());
        EvaluationLabelsFile.ValidatedLabels validated = EvaluationLabelsFile.readAndAudit(
                calibration ? manifest.calibrationLabels() : manifest.finalLabels(),
                study.queries(),
                study.corpus(),
                pairs);
        System.out.printf(
                Locale.ROOT,
                "Labels valid (%s): %d judgments, agreement=%.2f%%, kappa=%s%n",
                part.csvValue(),
                validated.labels().size(),
                validated.agreement().agreementRate() * 100.0,
                validated.agreement().cohensKappa() == null
                        ? "undefined"
                        : String.format(Locale.ROOT, "%.4f", validated.agreement().cohensKappa()));
    }

    private static void evaluate(Options options) {
        EvaluationManifest manifest = manifest(options);
        EvaluationStudyRunner study = EvaluationStudyRunner.load(manifest);
        EvaluationSelectionFile.FrozenSelection frozen =
                EvaluationSelectionFile.read(manifest.selection());
        if (!frozen.verifies(manifest, study.grid())) {
            throw new IllegalArgumentException(
                    "Frozen selection does not match the study manifest");
        }

        List<EvaluationLabels.Pair> calibrationPairs = EvaluationCandidates.readPairs(
                manifest.calibrationCandidates(), study.queries(), study.corpus());
        EvaluationLabelsFile.ValidatedLabels calibration = EvaluationLabelsFile.readAndAudit(
                manifest.calibrationLabels(),
                study.queries(),
                study.corpus(),
                calibrationPairs);

        Map<EvaluationConfig, Map<String, List<SimilarityResult>>> calibrationRanking =
                study.rank(EvaluationSplit.Part.CALIBRATION, study.configs());
        Map<EvaluationConfig, EvaluationMetrics.Aggregate> calibrationAggregates =
                study.aggregates(
                        EvaluationSplit.Part.CALIBRATION, calibrationRanking, calibration.labels());
        EvaluationSelection.Selection recomputed = EvaluationSelection.select(
                study.configs(), manifest.baseline(), calibrationAggregates, study.grid());
        if (!recomputed.selected().sameAs(frozen.selected())
                || recomputed.precisionImproved() != frozen.precisionImproved()
                || !frozen.decision().equals(
                        recomputed.precisionImproved() ? "RUN_TEST" : "KEEP_BASELINE")) {
            throw new IllegalArgumentException(
                    "Frozen selection cannot be reproduced from calibration labels");
        }
        if (!frozen.selected().sameAs(manifest.baseline())
                && !study.configs().contains(frozen.selected())) {
            throw new IllegalArgumentException(
                    "Frozen selected configuration is outside the grid");
        }

        EvaluationConfig bestBaseline = EvaluationSelection.bestInMode(
                study.configs(), SimilarityMode.BASELINE,
                manifest.baseline(), calibrationAggregates, study.grid());
        EvaluationConfig bestPhrases = EvaluationSelection.bestInMode(
                study.configs(), SimilarityMode.PHRASES,
                manifest.baseline(), calibrationAggregates, study.grid());

        Map<String, String> checksums = new LinkedHashMap<>(
                calibrationChecksums(manifest, study, frozen.selected()));
        for (Map.Entry<String, String> entry : frozen.checksums().entrySet()) {
            String actual = checksums.get(entry.getKey());
            if (actual == null || !actual.equals(entry.getValue())) {
                throw new IllegalArgumentException(
                        "Frozen checksum mismatch for " + entry.getKey()
                                + ": expected " + entry.getValue() + ", got " + actual);
            }
        }

        boolean testUsed = frozen.precisionImproved()
                && "RUN_TEST".equals(frozen.decision());
        Map<String, List<SimilarityResult>> testBaseline = Map.of();
        Map<String, List<SimilarityResult>> testSelected = Map.of();
        EvaluationLabels finalLabels = null;
        EvaluationLabelsFile.AgreementSummary testAgreement = null;
        Map<String, String> packetChecksums = packetChecksums(manifest);
        if (testUsed) {
            List<EvaluationLabels.Pair> finalPairs = EvaluationCandidates.readPairs(
                    manifest.finalCandidates(), study.queries(), study.corpus());
            EvaluationLabelsFile.ValidatedLabels finalValidated =
                    EvaluationLabelsFile.readAndAudit(
                            manifest.finalLabels(),
                            study.queries(),
                            study.corpus(),
                            finalPairs);
            finalLabels = finalValidated.labels();
            testAgreement = finalValidated.agreement();
            testBaseline = study.rank(EvaluationSplit.Part.TEST, manifest.baseline());
            testSelected = study.rank(EvaluationSplit.Part.TEST, frozen.selected());
            addFinalChecksums(
                    checksums, manifest, study, frozen.selected(), finalValidated);
            packetChecksums = packetChecksums(manifest);
        }

        Map<String, List<SimilarityResult>> calibrationBaseline =
                calibrationRanking.get(manifest.baseline());
        Map<String, List<SimilarityResult>> calibrationSelected =
                calibrationRanking.get(frozen.selected());
        EvaluationRunResult run = new EvaluationRunResult(
                study,
                frozen,
                calibration.labels(),
                calibration.agreement(),
                finalLabels,
                testAgreement,
                calibrationRanking,
                calibrationAggregates,
                calibrationBaseline,
                calibrationSelected,
                testBaseline,
                testSelected,
                testUsed,
                bestBaseline,
                bestPhrases,
                checksums,
                packetChecksums);
        EvaluationReportWriter.write(manifest.resultsDirectory(), run);

        System.out.printf(
                Locale.ROOT,
                "Baseline %s: cal P@5=%.3f, test P@5=%s, negative failures=%d%n",
                manifest.baseline().label(),
                calibrationAggregates.get(manifest.baseline()).meanPrecisionAt5(),
                testUsed
                        ? String.format(Locale.ROOT, "%.3f", run.testBaseline().meanPrecisionAt5())
                        : "unused",
                testUsed
                        ? run.testBaseline().negativeFailures()
                        : calibrationAggregates.get(manifest.baseline()).negativeFailures());
        System.out.printf(
                Locale.ROOT,
                "Selected %s: cal P@5=%.3f, test P@5=%s, negative failures=%d%n",
                frozen.selected().label(),
                calibrationAggregates.get(frozen.selected()).meanPrecisionAt5(),
                testUsed
                        ? String.format(Locale.ROOT, "%.3f", run.testSelected().meanPrecisionAt5())
                        : "unused",
                testUsed
                        ? run.testSelected().negativeFailures()
                        : calibrationAggregates.get(frozen.selected()).negativeFailures());
        System.out.println("Decision: " + run.decision());
        System.out.println("Artifacts: " + manifest.resultsDirectory());
    }

    private static void compare(Options options) {
        EvaluationManifest manifest = manifest(options);
        try {
            System.out.println(EvaluationReportWriter.compareSummary(
                    manifest.resultsDirectory().resolve("summary.json")));
        } catch (java.io.IOException failure) {
            throw new IllegalArgumentException("Cannot read evaluation summary", failure);
        }
    }

    private static Map<String, String> calibrationChecksums(
            EvaluationManifest manifest,
            EvaluationStudyRunner study,
            EvaluationConfig selected) {
        Map<String, String> checksums = commonChecksums(manifest, study, selected);
        checksums.put("calibrationCandidates",
                EvaluationChecksums.file(manifest.calibrationCandidates()));
        checksums.put("calibrationLabels",
                EvaluationChecksums.file(manifest.calibrationLabels().finalLabels()));
        checksums.put("calibrationReviewA",
                EvaluationChecksums.file(manifest.calibrationLabels().reviewerA()));
        checksums.put("calibrationReviewB",
                EvaluationChecksums.file(manifest.calibrationLabels().reviewerB()));
        checksums.put("calibrationAdjudication",
                EvaluationChecksums.file(manifest.calibrationLabels().adjudication()));
        String packet = packetChecksums(manifest).get("calibration");
        if (packet == null) {
            throw new IllegalArgumentException(
                    "Calibration review packets have not been exported");
        }
        checksums.put("calibrationPackets", packet);
        return checksums;
    }

    private static void addFinalChecksums(
            Map<String, String> checksums,
            EvaluationManifest manifest,
            EvaluationStudyRunner study,
            EvaluationConfig selected,
            EvaluationLabelsFile.ValidatedLabels finalValidated) {
        checksums.put("finalCandidates",
                EvaluationChecksums.file(manifest.finalCandidates()));
        checksums.put("finalLabels",
                EvaluationChecksums.file(manifest.finalLabels().finalLabels()));
        checksums.put("finalReviewA",
                EvaluationChecksums.file(manifest.finalLabels().reviewerA()));
        checksums.put("finalReviewB",
                EvaluationChecksums.file(manifest.finalLabels().reviewerB()));
        checksums.put("finalAdjudication",
                EvaluationChecksums.file(manifest.finalLabels().adjudication()));
        String packet = packetChecksums(manifest).get("final");
        if (packet == null) {
            throw new IllegalArgumentException("Final review packets have not been exported");
        }
        checksums.put("finalPackets", packet);
    }

    private static Map<String, String> commonChecksums(
            EvaluationManifest manifest,
            EvaluationStudyRunner study,
            EvaluationConfig selected) {
        Map<String, String> checksums = new LinkedHashMap<>();
        checksums.put("code", EvaluationChecksums.codeCombined(manifest.repositoryRoot()));
        checksums.put("dictionary", EvaluationChecksums.dictionary());
        checksums.put("queries", EvaluationChecksums.file(manifest.queries()));
        checksums.put("split", EvaluationChecksums.file(manifest.split()));
        checksums.put("studyManifest", EvaluationChecksums.file(manifest.manifestPath()));
        checksums.put("grid", EvaluationChecksums.sha256(
                study.grid().toString().getBytes(StandardCharsets.UTF_8)));
        checksums.put("reviewPrompt", EvaluationChecksums.file(manifest.reviewPrompt()));
        checksums.put("adjudicationPrompt",
                EvaluationChecksums.file(manifest.adjudicationPrompt()));
        checksums.put("reviewers", EvaluationChecksums.file(manifest.reviewers()));
        checksums.put("parameters", EvaluationChecksums.parameters(selected));
        return checksums;
    }

    private static Map<String, String> packetChecksums(EvaluationManifest manifest) {
        if (!Files.exists(manifest.packetChecksums())) {
            return Map.of();
        }
        try {
            JsonNode root = JSON.readTree(
                    Files.readString(manifest.packetChecksums(), StandardCharsets.UTF_8));
            Map<String, String> checksums = new LinkedHashMap<>();
            root.propertyNames().forEach(name -> {
                JsonNode value = root.get(name);
                if (value != null && value.isTextual()) {
                    checksums.put(name, value.asString());
                }
            });
            return Collections.unmodifiableMap(new LinkedHashMap<>(checksums));
        } catch (IOException failure) {
            throw new IllegalArgumentException(
                    "Cannot read packet checksums: " + manifest.packetChecksums(), failure);
        }
    }

    private static void updatePacketChecksums(
            Path path,
            String part,
            String packetHash) {
        Map<String, String> checksums = new LinkedHashMap<>();
        if (Files.exists(path)) {
            try {
                JsonNode root = JSON.readTree(Files.readString(path, StandardCharsets.UTF_8));
                root.propertyNames().forEach(name -> {
                    JsonNode value = root.get(name);
                    if (value != null && value.isTextual()) {
                        checksums.put(name, value.asString());
                    }
                });
            } catch (IOException failure) {
                throw new IllegalArgumentException(
                        "Cannot read packet checksums: " + path, failure);
            }
        }
        checksums.put(part, packetHash);
        ObjectNode root = JSON.createObjectNode();
        checksums.forEach(root::put);
        try {
            Files.createDirectories(path.toAbsolutePath().getParent());
            Files.writeString(
                    path,
                    JSON.writerWithDefaultPrettyPrinter()
                                    .writeValueAsString(root)
                                    .replace("\r\n", "\n")
                            + "\n",
                    StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalArgumentException("Cannot write packet checksums: " + path, failure);
        }
    }

    private static EvaluationSplit.Part part(Options options, boolean required) {
        String value = options.value("part");
        if (value == null) {
            if (required) {
                throw new IllegalArgumentException("Missing --part calibration|final");
            }
            return null;
        }
        return switch (value.strip().toLowerCase(Locale.ROOT)) {
            case "calibration" -> EvaluationSplit.Part.CALIBRATION;
            case "final", "test" -> EvaluationSplit.Part.TEST;
            default -> throw new IllegalArgumentException(
                    "--part must be calibration or final: " + value);
        };
    }

    private static EvaluationManifest manifest(Options options) {
        return EvaluationManifest.load(options.path("study", STUDY));
    }

    private static Path defaultStudy() {
        Path rootRelative = Path.of("docs/evaluation/v2/manifest.json");
        if (Files.exists(rootRelative)) {
            return rootRelative;
        }
        return Path.of("../docs/evaluation/v2/manifest.json");
    }

    private static void usage() {
        System.out.println("""
                Usage: evaluation <command> [--option value]

                Commands:
                  write-split         write the deterministic grouped stratified split
                  export-candidates   export the blinded top-5 union (--part calibration|final)
                  select              select using calibration labels only and freeze checksums
                  validate-labels     fail on missing, duplicate, or incomplete adjudication
                  evaluate            reproduce, run the unused final test if eligible, write results
                  compare             print the stored baseline/selected comparison

                Options:
                  --study <path>   study manifest (default docs/evaluation/v2/manifest.json)
                  --part <name>    calibration or final where required
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

        String value(String name) {
            return values.get(name);
        }

        Path path(String name, Path defaultValue) {
            String value = values.get(name);
            return value == null ? defaultValue : Path.of(value);
        }
    }
}
