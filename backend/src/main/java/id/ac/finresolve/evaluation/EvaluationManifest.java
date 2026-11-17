package id.ac.finresolve.evaluation;

import id.ac.finresolve.similarity.SimilarityMode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Study configuration loaded from {@code docs/evaluation/v2/manifest.json}.
 *
 * <p>Query counts, split targets, paths, modes, and calibration bounds live in the manifest so the
 * runner does not depend on constants from the archived first study.
 */
public final class EvaluationManifest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final Path manifestPath;
    private final Path baseDirectory;
    private final Path repositoryRoot;
    private final String studyId;
    private final String createdAt;
    private final boolean humanValidated;
    private final Path sampleCsv;
    private final Path sampleManifest;
    private final Path queries;
    private final Path split;
    private final Path calibrationCandidates;
    private final Path finalCandidates;
    private final Path selection;
    private final Path resultsDirectory;
    private final LabelPaths calibrationLabels;
    private final LabelPaths finalLabels;
    private final Path packetsDirectory;
    private final Path packetChecksums;
    private final Path reviewers;
    private final Path reviewPrompt;
    private final Path adjudicationPrompt;
    private final EvaluationSplit.SplitSpec splitSpec;
    private final EvaluationConfig.EvaluationGrid grid;
    private final Expected expected;
    private final EvaluationConfig baseline;
    private final EvaluationConfig rollback;
    private final String dictionarySha256;

    private EvaluationManifest(
            Path manifestPath,
            Path baseDirectory,
            Path repositoryRoot,
            String studyId,
            String createdAt,
            boolean humanValidated,
            Path sampleCsv,
            Path sampleManifest,
            Path queries,
            Path split,
            Path calibrationCandidates,
            Path finalCandidates,
            Path selection,
            Path resultsDirectory,
            LabelPaths calibrationLabels,
            LabelPaths finalLabels,
            Path packetsDirectory,
            Path packetChecksums,
            Path reviewers,
            Path reviewPrompt,
            Path adjudicationPrompt,
            EvaluationSplit.SplitSpec splitSpec,
            EvaluationConfig.EvaluationGrid grid,
            Expected expected,
            EvaluationConfig baseline,
            EvaluationConfig rollback,
            String dictionarySha256) {
        this.manifestPath = manifestPath;
        this.baseDirectory = baseDirectory;
        this.repositoryRoot = repositoryRoot;
        this.studyId = studyId;
        this.createdAt = createdAt;
        this.humanValidated = humanValidated;
        this.sampleCsv = sampleCsv;
        this.sampleManifest = sampleManifest;
        this.queries = queries;
        this.split = split;
        this.calibrationCandidates = calibrationCandidates;
        this.finalCandidates = finalCandidates;
        this.selection = selection;
        this.resultsDirectory = resultsDirectory;
        this.calibrationLabels = calibrationLabels;
        this.finalLabels = finalLabels;
        this.packetsDirectory = packetsDirectory;
        this.packetChecksums = packetChecksums;
        this.reviewers = reviewers;
        this.reviewPrompt = reviewPrompt;
        this.adjudicationPrompt = adjudicationPrompt;
        this.splitSpec = splitSpec;
        this.grid = grid;
        this.expected = expected;
        this.baseline = baseline;
        this.rollback = rollback;
        this.dictionarySha256 = dictionarySha256;
    }

    public static EvaluationManifest load(Path manifestPath) {
        Path absolute = manifestPath.toAbsolutePath().normalize();
        JsonNode root = read(absolute);
        Path base = absolute.getParent();
        JsonNode splitNode = required(root, "split");
        JsonNode expectedNode = required(root, "expected");
        JsonNode labelsNode = required(root, "labels");
        JsonNode subagents = required(root, "subagents");
        JsonNode gridNode = required(root, "grid");

        List<SimilarityMode> modes = new ArrayList<>();
        for (JsonNode mode : required(gridNode, "modes")) {
            if (!mode.isTextual()) {
                throw new IllegalArgumentException("grid.modes must contain strings");
            }
            modes.add(SimilarityMode.parse(mode.asString()));
        }

        EvaluationConfig.EvaluationGrid grid = new EvaluationConfig.EvaluationGrid(
                modes,
                requiredDouble(gridNode, "categoryWeightMin"),
                requiredDouble(gridNode, "categoryWeightMax"),
                requiredDouble(gridNode, "categoryWeightStep"),
                requiredDouble(gridNode, "thresholdMin"),
                requiredDouble(gridNode, "thresholdMax"),
                requiredDouble(gridNode, "thresholdStep"),
                requiredDouble(gridNode, "productWeight"),
                requiredDouble(gridNode, "subProductWeight"),
                requiredDouble(gridNode, "issueWeight"),
                requiredDouble(gridNode, "subIssueWeight"),
                requiredInt(gridNode, "limit"));

        String dictionarySha = optionalText(root, "dictionarySha256");
        return new EvaluationManifest(
                absolute,
                base,
                resolve(base, requiredText(root, "repositoryRoot")),
                requiredText(root, "studyId"),
                requiredText(root, "createdAt"),
                requiredBoolean(root, "humanValidated"),
                resolve(base, requiredText(root, "sampleCsv")),
                resolve(base, requiredText(root, "sampleManifest")),
                resolve(base, requiredText(root, "queries")),
                resolve(base, requiredText(root, "splitFile")),
                resolve(base, requiredText(root, "calibrationCandidates")),
                resolve(base, requiredText(root, "finalCandidates")),
                resolve(base, requiredText(root, "selection")),
                resolve(base, requiredText(root, "results")),
                labelPaths(base, required(labelsNode, "calibration")),
                labelPaths(base, required(labelsNode, "final")),
                resolve(base, requiredText(subagents, "packets")),
                resolve(base, requiredText(subagents, "packetChecksums")),
                resolve(base, requiredText(subagents, "reviewers")),
                resolve(base, requiredText(subagents, "reviewPrompt")),
                resolve(base, requiredText(subagents, "adjudicationPrompt")),
                new EvaluationSplit.SplitSpec(
                        requiredLong(splitNode, "seed"),
                        requiredInt(splitNode, "studyCalibrationComplaints"),
                        requiredInt(splitNode, "studyCalibrationNegatives")),
                grid,
                new Expected(
                        requiredInt(expectedNode, "total"),
                        requiredInt(expectedNode, "complaints"),
                        requiredInt(expectedNode, "negatives"),
                        requiredInt(expectedNode, "calibrationTotal"),
                        requiredInt(expectedNode, "calibrationComplaints"),
                        requiredInt(expectedNode, "calibrationNegatives"),
                        requiredInt(expectedNode, "testTotal"),
                        requiredInt(expectedNode, "testComplaints"),
                        requiredInt(expectedNode, "testNegatives"),
                        requiredInt(expectedNode, "studyGroups")),
                config(required(root, "baseline")),
                config(required(root, "rollback")),
                dictionarySha);
    }

    private static LabelPaths labelPaths(Path base, JsonNode node) {
        return new LabelPaths(
                resolve(base, requiredText(node, "final")),
                resolve(base, requiredText(node, "reviewerA")),
                resolve(base, requiredText(node, "reviewerB")),
                resolve(base, requiredText(node, "adjudication")));
    }

    private static EvaluationConfig config(JsonNode node) {
        return new EvaluationConfig(
                SimilarityMode.parse(requiredText(node, "mode")),
                requiredDouble(node, "categoryWeight"),
                requiredDouble(node, "threshold"));
    }

    private static Path resolve(Path base, String value) {
        return base.resolve(value).normalize();
    }

    private static JsonNode read(Path path) {
        try {
            return JSON.readTree(Files.readString(path, StandardCharsets.UTF_8));
        } catch (IOException failure) {
            throw new IllegalArgumentException("Cannot read study manifest: " + path, failure);
        }
    }

    private static JsonNode required(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            throw new IllegalArgumentException("Manifest is missing field: " + field);
        }
        return value;
    }

    private static String requiredText(JsonNode node, String field) {
        JsonNode value = required(node, field);
        if (!value.isTextual() || value.asString().isBlank()) {
            throw new IllegalArgumentException("Manifest field must be text: " + field);
        }
        return value.asString();
    }

    private static String optionalText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw new IllegalArgumentException("Manifest field must be text: " + field);
        }
        return value.asString();
    }

    private static double requiredDouble(JsonNode node, String field) {
        JsonNode value = required(node, field);
        if (!value.isNumber()) {
            throw new IllegalArgumentException("Manifest field must be numeric: " + field);
        }
        return value.asDouble();
    }

    private static int requiredInt(JsonNode node, String field) {
        JsonNode value = required(node, field);
        if (!value.isIntegralNumber()) {
            throw new IllegalArgumentException("Manifest field must be an integer: " + field);
        }
        return value.asInt();
    }

    private static long requiredLong(JsonNode node, String field) {
        JsonNode value = required(node, field);
        if (!value.isIntegralNumber()) {
            throw new IllegalArgumentException("Manifest field must be an integer: " + field);
        }
        return value.asLong();
    }

    private static boolean requiredBoolean(JsonNode node, String field) {
        JsonNode value = required(node, field);
        if (!value.isBoolean()) {
            throw new IllegalArgumentException("Manifest field must be boolean: " + field);
        }
        return value.asBoolean();
    }

    public Path manifestPath() {
        return manifestPath;
    }

    public Path baseDirectory() {
        return baseDirectory;
    }

    public Path repositoryRoot() {
        return repositoryRoot;
    }

    public String studyId() {
        return studyId;
    }

    public String createdAt() {
        return createdAt;
    }

    public boolean humanValidated() {
        return humanValidated;
    }

    public Path sampleCsv() {
        return sampleCsv;
    }

    public Path sampleManifest() {
        return sampleManifest;
    }

    public Path queries() {
        return queries;
    }

    public Path split() {
        return split;
    }

    public Path calibrationCandidates() {
        return calibrationCandidates;
    }

    public Path finalCandidates() {
        return finalCandidates;
    }

    public Path selection() {
        return selection;
    }

    public Path resultsDirectory() {
        return resultsDirectory;
    }

    public LabelPaths calibrationLabels() {
        return calibrationLabels;
    }

    public LabelPaths finalLabels() {
        return finalLabels;
    }

    public Path packetsDirectory() {
        return packetsDirectory;
    }

    public Path packetChecksums() {
        return packetChecksums;
    }

    public Path reviewers() {
        return reviewers;
    }

    public Path reviewPrompt() {
        return reviewPrompt;
    }

    public Path adjudicationPrompt() {
        return adjudicationPrompt;
    }

    public EvaluationSplit.SplitSpec splitSpec() {
        return splitSpec;
    }

    public EvaluationConfig.EvaluationGrid grid() {
        return grid;
    }

    public Expected expected() {
        return expected;
    }

    public EvaluationConfig baseline() {
        return baseline;
    }

    public EvaluationConfig rollback() {
        return rollback;
    }

    public String dictionarySha256() {
        return dictionarySha256;
    }

    public record LabelPaths(
            Path finalLabels, Path reviewerA, Path reviewerB, Path adjudication) {
    }

    public record Expected(
            int total,
            int complaints,
            int negatives,
            int calibrationTotal,
            int calibrationComplaints,
            int calibrationNegatives,
            int testTotal,
            int testComplaints,
            int testNegatives,
            int studyGroups) {
    }
}
