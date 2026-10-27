package id.ac.finresolve.evaluation;

import id.ac.finresolve.evaluation.EvaluationConfig.EvaluationGrid;
import id.ac.finresolve.domain.similarity.SimilarityMode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Frozen calibration selection and its checksums. */
public final class EvaluationSelectionFile {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private EvaluationSelectionFile() {
    }

    public static void write(
            Path path,
            EvaluationManifest manifest,
            EvaluationSelection.Selection selection,
            Map<EvaluationConfig, EvaluationMetrics.Aggregate> calibration,
            EvaluationConfig bestBaselineMode,
            EvaluationConfig bestPhrasesMode,
            Map<String, String> checksums) {
        ObjectNode root = JSON.createObjectNode();
        root.put("studyId", manifest.studyId());
        root.put("createdAt", manifest.createdAt());
        root.put("humanValidated", false);
        root.put("decision", selection.precisionImproved() ? "RUN_TEST" : "KEEP_BASELINE");
        root.put("precisionImproved", selection.precisionImproved());
        root.put("eligibleCandidates", selection.eligibleCandidates());
        root.set("baseline", configNode(manifest.baseline()));
        root.set("selected", configNode(selection.selected()));
        root.set("bestEligible", configNode(selection.bestEligible()));
        if (bestBaselineMode != null) {
            root.set("bestBaselineMode", configNode(bestBaselineMode));
        }
        if (bestPhrasesMode != null) {
            root.set("bestPhrasesMode", configNode(bestPhrasesMode));
        }
        root.set("calibration", JSON.createObjectNode()
                .set("baseline", aggregateNode(calibration.get(manifest.baseline())))
                .set("selected", aggregateNode(calibration.get(selection.selected()))));
        ObjectNode checksumNode = root.putObject("checksums");
        checksums.forEach(checksumNode::put);
        try {
            Files.createDirectories(path.toAbsolutePath().getParent());
            String json = JSON.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(root)
                    .replace("\r\n", "\n")
                    + "\n";
            Files.writeString(path, json, StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalArgumentException("Cannot write selection: " + path, failure);
        }
    }

    public static FrozenSelection read(Path path) {
        try {
            JsonNode root = JSON.readTree(Files.readString(path, StandardCharsets.UTF_8));
            Map<String, String> checksums = new LinkedHashMap<>();
            JsonNode checksumNode = root.get("checksums");
            if (checksumNode == null || !checksumNode.isObject()) {
                throw new IllegalArgumentException("Selection is missing checksums");
            }
            checksumNode.propertyNames().forEach(name ->
                    checksums.put(name, checksumNode.get(name).asString()));
            return new FrozenSelection(
                    root.get("studyId").asString(),
                    config(root.get("baseline")),
                    config(root.get("selected")),
                    root.has("bestBaselineMode") ? config(root.get("bestBaselineMode")) : null,
                    root.has("bestPhrasesMode") ? config(root.get("bestPhrasesMode")) : null,
                    root.get("precisionImproved").asBoolean(),
                    root.has("eligibleCandidates") ? root.get("eligibleCandidates").asInt() : 0,
                    root.get("decision").asString(),
                    Map.copyOf(checksums));
        } catch (IOException failure) {
            throw new IllegalArgumentException("Cannot read selection: " + path, failure);
        }
    }

    private static ObjectNode configNode(EvaluationConfig config) {
        ObjectNode node = JSON.createObjectNode();
        node.put("mode", config.mode().configValue());
        node.put("algorithmVersion", config.mode().algorithmVersion());
        node.put("categoryWeight", config.categoryWeight());
        node.put("textWeight", config.textWeight());
        node.put("threshold", config.threshold());
        node.put("label", config.label());
        return node;
    }

    static ObjectNode aggregateNode(EvaluationMetrics.Aggregate aggregate) {
        ObjectNode node = JSON.createObjectNode();
        node.put("complaintQueries", aggregate.complaintQueries());
        node.put("negativeQueries", aggregate.negativeQueries());
        node.put("meanPrecisionAt5", aggregate.meanPrecisionAt5());
        node.put("macroPrecision", aggregate.macroPrecision());
        node.put("microPrecision", aggregate.microPrecision());
        node.put("complaintSuccesses", aggregate.complaintSuccesses());
        node.put("complaintSuccessRate", aggregate.complaintSuccessRate());
        node.put("complaintReturned", aggregate.complaintReturned());
        node.put("relevantReturned", aggregate.relevantReturned());
        node.put("negativeReturned", aggregate.negativeReturned());
        node.put("totalReturned", aggregate.totalReturned());
        node.put("negativeFailures", aggregate.negativeFailures());
        node.put("negativeFailureRate", aggregate.negativeFailureRate());
        return node;
    }

    private static EvaluationConfig config(JsonNode node) {
        return new EvaluationConfig(
                SimilarityMode.parse(node.get("mode").asString()),
                node.get("categoryWeight").asDouble(),
                node.get("threshold").asDouble());
    }

    public record FrozenSelection(
            String studyId,
            EvaluationConfig baseline,
            EvaluationConfig selected,
            EvaluationConfig bestBaselineMode,
            EvaluationConfig bestPhrasesMode,
            boolean precisionImproved,
            int eligibleCandidates,
            String decision,
            Map<String, String> checksums) {

        public FrozenSelection {
            checksums = Map.copyOf(checksums);
        }

        public boolean verifies(EvaluationManifest manifest, EvaluationGrid grid) {
            if (!studyId.equals(manifest.studyId())) {
                return false;
            }
            if (!baseline.sameAs(manifest.baseline())) {
                return false;
            }
            if (decision.equals("KEEP_BASELINE")) {
                return selected.sameAs(manifest.baseline());
            }
            return decision.equals("RUN_TEST");
        }
    }
}
