package id.ac.finresolve.domain.similarity;

import java.util.Locale;

/** Text-scoring pipeline selected by {@code finresolve.similarity.mode}. */
public enum SimilarityMode {
    BASELINE("baseline", "tfidf-cosine-v1"),
    PHRASES("phrases", "tfidf-phrases-v2");

    private final String configValue;
    private final String algorithmVersion;

    SimilarityMode(String configValue, String algorithmVersion) {
        this.configValue = configValue;
        this.algorithmVersion = algorithmVersion;
    }

    public String configValue() {
        return configValue;
    }

    public String algorithmVersion() {
        return algorithmVersion;
    }

    /** Parses a configured mode and rejects unknown values before the application starts. */
    public static SimilarityMode parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("similarity mode is required");
        }
        String normalized = value.strip().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "baseline" -> BASELINE;
            case "phrases" -> PHRASES;
            default -> throw new IllegalArgumentException(
                    "Unknown similarity mode: " + value
                            + " (expected baseline or phrases)");
        };
    }
}
