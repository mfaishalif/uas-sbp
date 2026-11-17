package id.ac.finresolve.similarity;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Immutable, versioned domain phrase dictionary.
 *
 * <p>Variants are replaced by their canonical token before stopword removal. At each position the
 * longest matching variant wins; matches respect word boundaries, never inspect text already
 * replaced, and never span sentence punctuation. The original narrative is left untouched; only
 * the tokenizer input is normalized.
 */
public final class PhraseDictionary {

    public static final String VERSION = "domain-phrases-v1";
    public static final String RESOURCE = "/similarity/phrases-en-v1.txt";

    private static final PhraseDictionary DEFAULT = load();

    private final List<Phrase> phrases;
    private final String sha256;

    private PhraseDictionary(List<Phrase> phrases, String sha256) {
        this.phrases = List.copyOf(phrases);
        this.sha256 = sha256;
    }

    public static PhraseDictionary defaultDictionary() {
        return DEFAULT;
    }

    public String version() {
        return VERSION;
    }

    public String sha256() {
        return sha256;
    }

    /** Variants in match order (longest first) for audit tests and documentation. */
    public List<String> orderedVariants() {
        return phrases.stream().map(Phrase::variant).toList();
    }

    public String normalize(String text) {
        if (text == null || text.isEmpty() || phrases.isEmpty()) {
            return text;
        }

        StringBuilder normalized = new StringBuilder(text.length());
        int index = 0;
        while (index < text.length()) {
            int codePoint = text.codePointAt(index);
            if (isWordStart(text, index)) {
                Match match = longestMatch(text, index);
                if (match != null) {
                    normalized.append(match.canonical());
                    index = match.end();
                    continue;
                }
            }
            normalized.appendCodePoint(codePoint);
            index += Character.charCount(codePoint);
        }
        return normalized.toString();
    }

    private Match longestMatch(String text, int start) {
        for (Phrase phrase : phrases) {
            int end = phrase.matchAt(text, start);
            if (end >= 0) {
                return new Match(phrase.canonical(), end);
            }
        }
        return null;
    }

    private static boolean isWordStart(String text, int index) {
        return index == 0
                || !Character.isLetterOrDigit(text.codePointBefore(index));
    }

    private static PhraseDictionary load() {
        List<Phrase> phrases = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        byte[] bytes;
        try (InputStream input = PhraseDictionary.class.getResourceAsStream(RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException(
                        "Missing committed phrase dictionary: " + RESOURCE);
            }
            bytes = input.readAllBytes();
        } catch (IOException failure) {
            throw new IllegalStateException(
                    "Cannot read committed phrase dictionary: " + RESOURCE, failure);
        }

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(
                        new java.io.ByteArrayInputStream(bytes), StandardCharsets.UTF_8))) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                String stripped = line.strip();
                if (stripped.isEmpty() || stripped.startsWith("#")) {
                    continue;
                }
                String[] fields = stripped.split("\\t", -1);
                if (fields.length != 2) {
                    throw new IllegalStateException(
                            "Malformed phrase dictionary line " + lineNumber + ": " + line);
                }
                String canonical = fields[0].strip().toLowerCase(Locale.ROOT);
                String variant = fields[1].strip();
                if (!canonical.matches("[a-z][a-z0-9]*")) {
                    throw new IllegalStateException(
                            "Invalid canonical token on line " + lineNumber + ": " + canonical);
                }
                if (variant.isEmpty() || !variant.matches("[A-Za-z]+(?: [A-Za-z]+)*")) {
                    throw new IllegalStateException(
                            "Invalid phrase variant on line " + lineNumber + ": " + variant);
                }
                String key = variant.toLowerCase(Locale.ROOT);
                if (!seen.add(key)) {
                    throw new IllegalStateException(
                            "Duplicate phrase variant on line " + lineNumber + ": " + variant);
                }
                phrases.add(new Phrase(canonical, variant));
            }
        } catch (IOException failure) {
            throw new IllegalStateException(
                    "Cannot parse committed phrase dictionary: " + RESOURCE, failure);
        }

        if (phrases.isEmpty()) {
            throw new IllegalStateException("Phrase dictionary is empty: " + RESOURCE);
        }
        phrases.sort(Comparator
                .comparingInt((Phrase phrase) -> phrase.words().length)
                .reversed()
                .thenComparing(Comparator.comparingInt(Phrase::length).reversed())
                .thenComparing(Phrase::variant));
        return new PhraseDictionary(phrases, sha256(bytes));
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    private record Match(String canonical, int end) {
    }

    private record Phrase(String canonical, String variant, String[] words) {

        private Phrase(String canonical, String variant) {
            this(canonical, variant, variant.split(" "));
        }

        private int length() {
            return variant.length();
        }

        /**
         * Returns the exclusive end offset when the phrase starts at {@code start}, or -1.
         * Repeated whitespace is accepted between words; punctuation is not.
         */
        private int matchAt(String text, int start) {
            int position = start;
            for (int wordIndex = 0; wordIndex < words.length; wordIndex++) {
                if (wordIndex > 0) {
                    int whitespaceStart = position;
                    while (position < text.length()
                            && Character.isWhitespace(text.charAt(position))) {
                        position++;
                    }
                    if (position == whitespaceStart) {
                        return -1;
                    }
                }

                String word = words[wordIndex];
                if (position + word.length() > text.length()
                        || !text.regionMatches(true, position, word, 0, word.length())) {
                    return -1;
                }
                position += word.length();
                if (position < text.length()
                        && Character.isLetterOrDigit(text.codePointAt(position))) {
                    return -1;
                }
            }
            return position;
        }
    }
}
