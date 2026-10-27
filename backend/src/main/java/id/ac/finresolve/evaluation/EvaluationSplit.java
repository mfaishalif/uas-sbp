package id.ac.finresolve.evaluation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;

/**
 * Deterministic group-stratified split.
 *
 * <p>Development queries are always calibration data. New study groups are shuffled within each
 * kind and assigned to calibration until the manifest's study-calibration query counts are met.
 * Both variants of a group always receive the same part.
 */
public final class EvaluationSplit {

    private final Map<String, Part> parts;

    private EvaluationSplit(Map<String, Part> parts) {
        this.parts = Map.copyOf(parts);
    }

    public static EvaluationSplit create(List<EvaluationQuery> queries, SplitSpec spec) {
        Objects.requireNonNull(queries, "queries");
        Objects.requireNonNull(spec, "spec");
        if (spec.studyCalibrationComplaints() < 0
                || spec.studyCalibrationNegatives() < 0) {
            throw new IllegalArgumentException("Calibration counts must be non-negative");
        }

        Map<String, List<EvaluationQuery>> groups = new LinkedHashMap<>();
        for (EvaluationQuery query : queries) {
            groups.computeIfAbsent(query.groupId(), ignored -> new ArrayList<>()).add(query);
        }
        for (Map.Entry<String, List<EvaluationQuery>> entry : groups.entrySet()) {
            EvaluationQuery first = entry.getValue().get(0);
            for (EvaluationQuery query : entry.getValue()) {
                if (query.kind() != first.kind() || query.cohort() != first.cohort()) {
                    throw new IllegalArgumentException(
                            "Group " + entry.getKey() + " is not homogeneous");
                }
            }
        }

        Map<String, Part> parts = new LinkedHashMap<>();
        for (List<EvaluationQuery> group : groups.values()) {
            if (group.get(0).cohort() == EvaluationQuery.Cohort.DEVELOPMENT) {
                group.forEach(query -> parts.put(query.queryId(), Part.CALIBRATION));
            }
        }

        Random random = new Random(spec.seed());
        assignStudyGroups(
                groups, EvaluationQuery.Kind.COMPLAINT,
                spec.studyCalibrationComplaints(), random, parts);
        assignStudyGroups(
                groups, EvaluationQuery.Kind.NEGATIVE,
                spec.studyCalibrationNegatives(), random, parts);

        if (parts.size() != queries.size()) {
            throw new IllegalArgumentException("Split did not assign every query");
        }
        return new EvaluationSplit(parts);
    }

    /** Convenience for tests that only use study groups. */
    static EvaluationSplit create(
            List<EvaluationQuery> queries,
            long seed,
            int studyCalibrationComplaints,
            int studyCalibrationNegatives) {
        return create(queries, new SplitSpec(
                seed, studyCalibrationComplaints, studyCalibrationNegatives));
    }

    private static void assignStudyGroups(
            Map<String, List<EvaluationQuery>> groups,
            EvaluationQuery.Kind kind,
            int calibrationQueries,
            Random random,
            Map<String, Part> parts) {
        List<List<EvaluationQuery>> studyGroups = groups.values().stream()
                .filter(group -> group.get(0).cohort() == EvaluationQuery.Cohort.STUDY)
                .filter(group -> group.get(0).kind() == kind)
                .sorted(Comparator.comparing(group -> group.get(0).groupId()))
                .map(List::copyOf)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        Collections.shuffle(studyGroups, random);

        int assigned = 0;
        for (List<EvaluationQuery> group : studyGroups) {
            Part part;
            if (assigned < calibrationQueries) {
                if (assigned + group.size() > calibrationQueries) {
                    throw new IllegalArgumentException(
                            "Calibration count " + calibrationQueries
                                    + " does not align with group boundaries");
                }
                part = Part.CALIBRATION;
                assigned += group.size();
            } else {
                part = Part.TEST;
            }
            group.forEach(query -> parts.put(query.queryId(), part));
        }
        if (assigned != calibrationQueries) {
            throw new IllegalArgumentException(
                    "Calibration count " + calibrationQueries
                            + " is outside the available study " + kind + " groups");
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
        return queries.stream()
                .filter(query -> partOf(query.queryId()) == part)
                .toList();
    }

    public int count(Part part) {
        return (int) parts.values().stream().filter(value -> value == part).count();
    }

    public int count(Part part, EvaluationQuery.Kind kind, List<EvaluationQuery> queries) {
        return (int) queries.stream()
                .filter(query -> query.kind() == kind && partOf(query.queryId()) == part)
                .count();
    }

    public Map<String, Part> asMap() {
        return Map.copyOf(parts);
    }

    public record SplitSpec(
            long seed, int studyCalibrationComplaints, int studyCalibrationNegatives) {
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
