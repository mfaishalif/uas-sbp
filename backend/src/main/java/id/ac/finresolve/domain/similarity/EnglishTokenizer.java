package id.ac.finresolve.domain.similarity;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Deterministic English tokenization for the fixed TF-IDF vocabulary.
 *
 * <p>In {@link SimilarityMode#PHRASES} mode the domain phrase dictionary is applied first.
 * Text is then lowercased with {@link Locale#ROOT} before Unicode letter/digit runs are collected.
 * Numeric-only tokens, single-code-point tokens, redaction placeholders ({@code xx}, {@code xxx},
 * ...), and the committed English stopword list are discarded. Duplicate tokens are preserved.
 */
public final class EnglishTokenizer {

    private final Set<String> stopwords;
    private final PhraseDictionary dictionary;

    public EnglishTokenizer() {
        this(SimilarityMode.BASELINE);
    }

    public EnglishTokenizer(SimilarityMode mode) {
        if (mode == null) {
            throw new IllegalArgumentException("similarity mode is required");
        }
        this.stopwords = loadStopwords();
        this.dictionary = mode == SimilarityMode.PHRASES
                ? PhraseDictionary.defaultDictionary()
                : null;
    }

    public List<String> tokens(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }

        String normalized = dictionary == null ? text : dictionary.normalize(text);
        String lowered = normalized.toLowerCase(Locale.ROOT);
        List<String> tokens = new ArrayList<>();
        int index = 0;
        while (index < lowered.length()) {
            int codePoint = lowered.codePointAt(index);
            if (!Character.isLetterOrDigit(codePoint)) {
                index += Character.charCount(codePoint);
                continue;
            }

            int start = index;
            index += Character.charCount(codePoint);
            while (index < lowered.length()) {
                int next = lowered.codePointAt(index);
                if (!Character.isLetterOrDigit(next)) {
                    break;
                }
                index += Character.charCount(next);
            }

            String token = lowered.substring(start, index);
            if (isMeaningful(token)) {
                tokens.add(token);
            }
        }
        return List.copyOf(tokens);
    }

    private boolean isMeaningful(String token) {
        if (token.codePointCount(0, token.length()) <= 1) {
            return false;
        }
        if (token.codePoints().allMatch(Character::isDigit)) {
            return false;
        }
        if (isRedactionPlaceholder(token)) {
            return false;
        }
        return !stopwords.contains(token);
    }

    private static boolean isRedactionPlaceholder(String token) {
        return token.codePoints().allMatch(codePoint -> codePoint == 'x');
    }

    private static Set<String> loadStopwords() {
        try (InputStream input = EnglishTokenizer.class.getResourceAsStream(
                TextRules.STOPWORDS_RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException(
                        "Missing committed stopword resource: " + TextRules.STOPWORDS_RESOURCE);
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(input, StandardCharsets.UTF_8))) {
                return reader.lines()
                        .map(String::strip)
                        .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                        .collect(Collectors.toUnmodifiableSet());
            }
        } catch (IOException failure) {
            throw new IllegalStateException(
                    "Cannot read committed stopword resource: " + TextRules.STOPWORDS_RESOURCE,
                    failure);
        }
    }
}
