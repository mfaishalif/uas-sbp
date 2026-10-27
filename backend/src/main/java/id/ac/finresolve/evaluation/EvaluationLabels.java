package id.ac.finresolve.evaluation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Immutable binary judgments keyed by (query, CFPB complaint ID). */
public final class EvaluationLabels {

    private final Map<Pair, Judgment> judgments;

    private EvaluationLabels(Map<Pair, Judgment> judgments) {
        this.judgments = Map.copyOf(judgments);
    }

    public static EvaluationLabels of(Collection<Judgment> rows) {
        Map<Pair, Judgment> judgments = new LinkedHashMap<>();
        for (Judgment row : rows) {
            Pair key = new Pair(row.queryId(), row.cfpbId());
            if (judgments.putIfAbsent(key, row) != null) {
                throw new IllegalArgumentException(
                        "Duplicate judgment for " + row.queryId() + "/" + row.cfpbId());
            }
        }
        return new EvaluationLabels(judgments);
    }

    public boolean relevant(String queryId, String cfpbId) {
        return judgment(queryId, cfpbId).relevant();
    }

    public String reason(String queryId, String cfpbId) {
        return judgment(queryId, cfpbId).reason();
    }

    public String evidence(String queryId, String cfpbId) {
        return judgment(queryId, cfpbId).evidence();
    }

    public Judgment judgment(String queryId, String cfpbId) {
        Judgment judgment = judgments.get(new Pair(queryId, cfpbId));
        if (judgment == null) {
            throw new IllegalArgumentException(
                    "Missing judgment for " + queryId + "/" + cfpbId);
        }
        return judgment;
    }

    /** Fails when any pooled pair has no judgment; absent labels are never treated as irrelevant. */
    public void requireCoverage(Collection<Pair> expectedPairs) {
        List<String> missing = new ArrayList<>();
        for (Pair pair : expectedPairs) {
            if (!judgments.containsKey(pair)) {
                missing.add(pair.queryId() + "/" + pair.cfpbId());
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException(
                    "Missing judgments (" + missing.size() + "): "
                            + String.join(", ", missing));
        }
    }

    public int size() {
        return judgments.size();
    }

    public record Judgment(
            String queryId,
            String cfpbId,
            boolean relevant,
            String reason,
            String evidence,
            String reviewer) {

        public Judgment {
            if (queryId == null || queryId.isBlank()) {
                throw new IllegalArgumentException("Judgment queryId is required");
            }
            if (cfpbId == null || !cfpbId.matches("[1-9][0-9]*")) {
                throw new IllegalArgumentException(
                        "Judgment CFPB ID must be a positive integer: " + cfpbId);
            }
            if (reason == null || reason.isBlank()) {
                throw new IllegalArgumentException(
                        "Judgment reason is required for " + queryId + "/" + cfpbId);
            }
            if (evidence == null || evidence.isBlank()) {
                throw new IllegalArgumentException(
                        "Judgment evidence is required for " + queryId + "/" + cfpbId);
            }
            if (reviewer == null || reviewer.isBlank()) {
                throw new IllegalArgumentException(
                        "Judgment reviewer is required for " + queryId + "/" + cfpbId);
            }
        }
    }

    public record Pair(String queryId, String cfpbId) {
    }
}
