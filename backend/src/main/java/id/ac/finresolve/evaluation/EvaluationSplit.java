package id.ac.finresolve.evaluation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;

/** Deterministic stratified split of frozen queries into calibration and final-test parts. */
public final class EvaluationSplit {

    public static final long SEED = 20261027L;
    public static final int CALIBRATION_COMPLAINTS = 10;
    public static final int CALIBRATION_NEGATIVES = 4;

    private final Map<String, Part> parts;

    private EvaluationSplit(Map<String, Part> parts) {
        this.parts = Map.copyOf(parts);
    }

    public static EvaluationSplit create(List<EvaluationQuery> queries) {
        return create(
                queries,
                SEED,
                CALIBRATION_COMPLAINTS,
                CALIBRATION_NEGATIVES);
    }

    static EvaluationSplit create(
            List<EvaluationQuery> queries,
            long seed,
            int calibrationComplaints,
            int calibrationNegatives) {
        Objects.requireNonNull(queries, "queries");
        List<EvaluationQuery> complaints = queries.stream()
                .filter(query -> query.kind() == EvaluationQuery.Kind.COMPLAINT)
                .sorted(Comparator.comparing(EvaluationQuery::queryId))
                .toList();
        List<EvaluationQuery> negatives = queries.stream()
                .filter(query -> query.kind() == EvaluationQuery.Kind.NEGATIVE)
                .sorted(Comparator.comparing(EvaluationQuery::queryId))
                .toList();
        if (calibrationComplaints < 0 || calibrationComplaints > complaints.size()) {
            throw new IllegalArgumentException(
                    "Calibration complaint count out of range: " + calibrationComplaints);
        }
        if (calibrationNegatives < 0 || calibrationNegatives > negatives.size()) {
            throw new IllegalArgumentException(
                    "Calibration negative count out of range: " + calibrationNegatives);
        }

        Random random = new Random(seed);
        Map<String, Part> parts = new LinkedHashMap<>();
        assign(complaints, calibrationComplaints, random, parts);
        assign(negatives, calibrationNegatives, random, parts);
        if (parts.size() != queries.size()) {
            throw new IllegalArgumentException("Split did not assign every query");
        }
        return new EvaluationSplit(parts);
    }

    private static void assign(
            List<EvaluationQuery> queries,
            int calibrationCount,
            Random random,
            Map<String, Part> parts) {
        List<EvaluationQuery> shuffled = new ArrayList<>(queries);
        java.util.Collections.shuffle(shuffled, random);
        for (int index = 0; index < shuffled.size(); index++) {
            parts.put(
                    shuffled.get(index).queryId(),
                    index < calibrationCount ? Part.CALIBRATION : Part.TEST);
        }
    }

    public Part partOf(String queryId) {
        Part part = parts.get(queryId);
        if (part == null) {
            throw new IllegalArgumentException("Query is missing from the split: " + queryId);
        }
        return part;
    }

    public List<EvaluationQuery> queriesIn(List<EvaluationQuery> queries, Part part) {
        List<EvaluationQuery> selected = queries.stream()
                .filter(query -> partOf(query.queryId()) == part)
                .toList();
        return List.copyOf(selected);
    }

    public int count(Part part) {
        return (int) parts.values().stream().filter(value -> value == part).count();
    }

    public Map<String, Part> asMap() {
        return Map.copyOf(parts);
    }

    public enum Part {
        CALIBRATION,
        TEST;

        public String csvValue() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }

        public static Part parse(String value) {
            if (value == null) {
                throw new IllegalArgumentException("split is required");
            }
            return switch (value.strip().toLowerCase(java.util.Locale.ROOT)) {
                case "calibration" -> CALIBRATION;
                case "test" -> TEST;
                default -> throw new IllegalArgumentException(
                        "split must be calibration or test: " + value);
            };
        }
    }
}
