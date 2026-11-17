package id.ac.finresolve.similarity;

import id.ac.finresolve.cases.CaseRecord;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable TF-IDF snapshot fitted on case narratives only.
 *
 * <p>Terms use raw frequency multiplied by smoothed IDF
 * {@code ln((N + 1) / (df + 1)) + 1}; document and query vectors are L2-normalized. Query terms
 * outside the fitted vocabulary are ignored. Shared-term contributions are the normalized
 * query-case products, ordered by contribution descending then token ascending, and limited to ten.
 */
public final class TfidfSnapshot {

    private static final int MAX_SHARED_TERMS = 10;

    private final int corpusSize;
    private final Map<String, Double> idf;
    private final Map<UUID, Map<String, Double>> vectors;
    private final EnglishTokenizer tokenizer;

    private TfidfSnapshot(
            int corpusSize,
            Map<String, Double> idf,
            Map<UUID, Map<String, Double>> vectors,
            EnglishTokenizer tokenizer) {
        this.corpusSize = corpusSize;
        this.idf = Map.copyOf(idf);
        this.vectors = Map.copyOf(vectors);
        this.tokenizer = tokenizer;
    }

    public static TfidfSnapshot fit(List<CaseRecord> cases, EnglishTokenizer tokenizer) {
        Objects.requireNonNull(cases, "cases");
        Objects.requireNonNull(tokenizer, "tokenizer");

        List<Map<String, Integer>> frequencies = new ArrayList<>(cases.size());
        Map<String, Integer> documentFrequency = new HashMap<>();
        for (CaseRecord record : cases) {
            Map<String, Integer> counts = count(tokenizer.tokens(record.narrative()));
            frequencies.add(counts);
            for (String term : counts.keySet()) {
                documentFrequency.merge(term, 1, Integer::sum);
            }
        }

        int size = cases.size();
        Map<String, Double> idf = new HashMap<>();
        for (Map.Entry<String, Integer> entry : documentFrequency.entrySet()) {
            idf.put(entry.getKey(), Math.log((size + 1.0) / (entry.getValue() + 1.0)) + 1.0);
        }

        Map<UUID, Map<String, Double>> vectors = new LinkedHashMap<>();
        for (int index = 0; index < cases.size(); index++) {
            vectors.put(cases.get(index).id(), normalize(weights(frequencies.get(index), idf)));
        }
        return new TfidfSnapshot(size, idf, vectors, tokenizer);
    }

    public TextScore compare(String query, UUID caseId) {
        Objects.requireNonNull(caseId, "caseId");
        Map<String, Double> caseVector = vectors.get(caseId);
        if (caseVector == null) {
            throw new IllegalArgumentException("Unknown case id: " + caseId);
        }

        Map<String, Double> queryVector = normalize(weights(count(tokenizer.tokens(query)), idf));
        if (queryVector.isEmpty() || caseVector.isEmpty()) {
            return new TextScore(0.0, List.of());
        }

        List<Term> terms = new ArrayList<>();
        double score = 0.0;
        for (Map.Entry<String, Double> entry : queryVector.entrySet()) {
            Double caseWeight = caseVector.get(entry.getKey());
            if (caseWeight == null) {
                continue;
            }
            double contribution = entry.getValue() * caseWeight;
            terms.add(new Term(entry.getKey(), contribution));
            score += contribution;
        }

        terms.sort(Comparator.comparingDouble(Term::contribution)
                .reversed()
                .thenComparing(Term::term));
        List<Term> shared = terms.size() <= MAX_SHARED_TERMS
                ? terms
                : terms.subList(0, MAX_SHARED_TERMS);
        return new TextScore(Math.max(0.0, Math.min(1.0, score)), shared);
    }

    public int corpusSize() {
        return corpusSize;
    }

    double idf(String term) {
        Double value = idf.get(term);
        if (value == null) {
            throw new IllegalArgumentException("Unknown term: " + term);
        }
        return value;
    }

    private static Map<String, Integer> count(List<String> tokens) {
        Map<String, Integer> counts = new HashMap<>();
        for (String token : tokens) {
            counts.merge(token, 1, Integer::sum);
        }
        return counts;
    }

    private static Map<String, Double> weights(
            Map<String, Integer> frequencies, Map<String, Double> idf) {
        Map<String, Double> weights = new HashMap<>();
        for (Map.Entry<String, Integer> entry : frequencies.entrySet()) {
            Double inverse = idf.get(entry.getKey());
            if (inverse != null) {
                weights.put(entry.getKey(), entry.getValue() * inverse);
            }
        }
        return weights;
    }

    private static Map<String, Double> normalize(Map<String, Double> weights) {
        double normSquared = 0.0;
        for (double weight : weights.values()) {
            normSquared += weight * weight;
        }
        if (normSquared == 0.0) {
            return Map.of();
        }
        double norm = Math.sqrt(normSquared);
        Map<String, Double> normalized = new HashMap<>();
        for (Map.Entry<String, Double> entry : weights.entrySet()) {
            normalized.put(entry.getKey(), entry.getValue() / norm);
        }
        return normalized;
    }

    public record TextScore(double score, List<Term> sharedTerms) {

        public TextScore {
            sharedTerms = List.copyOf(sharedTerms == null ? List.of() : sharedTerms);
        }
    }

    public record Term(String term, double contribution) {
    }
}
