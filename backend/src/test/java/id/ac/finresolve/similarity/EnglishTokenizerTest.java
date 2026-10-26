package id.ac.finresolve.similarity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class EnglishTokenizerTest {

    private final EnglishTokenizer tokenizer = new EnglishTokenizer();

    @Test
    void removesStopwordsRedactionsNumericOnlyAndSingleCodePointTokens() {
        assertThat(tokenizer.tokens("THE transfer XX xxx 123 a account"))
                .containsExactly("transfer", "account");
        assertThat(tokenizer.tokens("fee fee refund")).containsExactly("fee", "fee", "refund");
    }

    @Test
    void lowercasesWithRootLocaleEvenWhenDefaultLocaleIsTurkish() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));

            assertThat(tokenizer.tokens("THE transfer XX xxx 123 a account"))
                    .containsExactly("transfer", "account");
            assertThat(tokenizer.tokens("fee fee refund")).containsExactly("fee", "fee", "refund");
            assertThat(tokenizer.tokens("INVALID TRANSFER"))
                    .containsExactly("invalid", "transfer");
            assertThat(tokenizer.tokens("I İ ı invalid")).containsExactly("invalid");
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    void treatsUnicodeLettersAndDigitsAsOneAlphanumericRun() {
        assertThat(tokenizer.tokens("INVALID TRANSFER")).containsExactly("invalid", "transfer");
        assertThat(tokenizer.tokens("Café TRANSFER2 １２３ 4f"))
                .containsExactly("café", "transfer2", "4f");
        assertThat(tokenizer.tokens("xxx XXXX xx1 x")).containsExactly("xx1");
    }

    @Test
    void nullAndBlankInputsProduceAnEmptyList() {
        assertThat(tokenizer.tokens(null)).isEmpty();
        assertThat(tokenizer.tokens("")).isEmpty();
        assertThat(tokenizer.tokens(" \t\n")).isEmpty();
    }

    @Test
    void returnsAnImmutableList() {
        List<String> tokens = tokenizer.tokens("transfer fee");

        assertThat(tokens).containsExactly("transfer", "fee");
        assertThat(tokens).isUnmodifiable();
    }

    @Test
    void pinsTheAlgorithmVersion() {
        assertThat(TextRules.ALGORITHM_VERSION).isEqualTo("tfidf-cosine-v1");
    }

    @Test
    void usesExactlyTheCommittedStopwordSet() throws Exception {
        List<String> expected = List.of(
                "a", "an", "and", "are", "as", "at", "be", "been", "being", "but", "by",
                "for", "from", "had", "has", "have", "he", "her", "hers", "him", "his", "i",
                "if", "in", "into", "is", "it", "its", "me", "my", "no", "not", "of", "on",
                "or", "our", "ours", "she", "so", "that", "the", "their", "theirs", "them",
                "they", "this", "to", "us", "was", "we", "were", "will", "with", "you",
                "your", "yours");

        try (var input = EnglishTokenizerTest.class.getResourceAsStream(
                TextRules.STOPWORDS_RESOURCE)) {
            assertThat(input).as("committed stopword resource").isNotNull();
            List<String> lines = new java.io.BufferedReader(
                            new java.io.InputStreamReader(input, java.nio.charset.StandardCharsets.UTF_8))
                    .lines()
                    .toList();
            assertThat(lines).containsExactlyInAnyOrderElementsOf(expected);
        }
    }
}
