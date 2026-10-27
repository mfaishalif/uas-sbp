package id.ac.finresolve.evaluation;

import id.ac.finresolve.domain.similarity.PhraseDictionary;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Freezes the algorithm, corpus, split, dictionary, prompts, and candidate parameters. */
public final class EvaluationChecksums {

    private static final List<String> CODE_FILES = List.of(
            "backend/src/main/java/id/ac/finresolve/domain/similarity/SimilarityMode.java",
            "backend/src/main/java/id/ac/finresolve/domain/similarity/PhraseDictionary.java",
            "backend/src/main/java/id/ac/finresolve/domain/similarity/EnglishTokenizer.java",
            "backend/src/main/java/id/ac/finresolve/domain/similarity/TextRules.java",
            "backend/src/main/java/id/ac/finresolve/domain/similarity/SimilaritySettings.java",
            "backend/src/main/java/id/ac/finresolve/domain/similarity/SimilarityEngine.java",
            "backend/src/main/java/id/ac/finresolve/domain/similarity/TfidfSnapshot.java",
            "backend/src/main/java/id/ac/finresolve/domain/similarity/CategoryScorer.java",
            "backend/src/main/java/id/ac/finresolve/domain/similarity/SimilarityResult.java",
            "backend/src/main/java/id/ac/finresolve/evaluation/EvaluationRunner.java",
            "backend/src/main/java/id/ac/finresolve/evaluation/EvaluationConfig.java",
            "backend/src/main/java/id/ac/finresolve/evaluation/EvaluationMetrics.java",
            "backend/src/main/java/id/ac/finresolve/evaluation/EvaluationSelection.java",
            "backend/src/main/java/id/ac/finresolve/evaluation/EvaluationCandidates.java");

    private EvaluationChecksums() {
    }

    public static Map<String, String> code(Path repositoryRoot) {
        Map<String, String> hashes = new LinkedHashMap<>();
        try {
            MessageDigest combined = MessageDigest.getInstance("SHA-256");
            for (String relative : CODE_FILES) {
                Path path = repositoryRoot.resolve(relative);
                byte[] bytes = Files.readAllBytes(path);
                String hash = HexFormat.of().formatHex(
                        MessageDigest.getInstance("SHA-256").digest(bytes));
                hashes.put(relative, hash);
                combined.update(relative.getBytes(StandardCharsets.UTF_8));
                combined.update((byte) 0);
                combined.update(bytes);
                combined.update((byte) 0);
            }
            hashes.put("combined", HexFormat.of().formatHex(combined.digest()));
            return Map.copyOf(hashes);
        } catch (IOException | NoSuchAlgorithmException failure) {
            throw new IllegalArgumentException("Cannot checksum scoring code", failure);
        }
    }

    public static String codeCombined(Path repositoryRoot) {
        return code(repositoryRoot).get("combined");
    }

    public static String dictionary() {
        return PhraseDictionary.defaultDictionary().sha256();
    }

    public static String parameters(EvaluationConfig config) {
        return sha256((config.mode().configValue() + "|" + config.categoryWeight() + "|"
                + config.threshold() + "|0.35|0.15|0.35|0.15|5")
                .getBytes(StandardCharsets.UTF_8));
    }

    public static String file(Path path) {
        try {
            return sha256(Files.readAllBytes(path));
        } catch (IOException failure) {
            throw new IllegalArgumentException("Cannot checksum file: " + path, failure);
        }
    }

    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }
}
