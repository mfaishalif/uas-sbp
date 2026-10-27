package id.ac.finresolve.domain.similarity;

/** Immutable rules shared by every tokenizer and TF-IDF consumer. */
public final class TextRules {

    public static final String STOPWORDS_RESOURCE = "/similarity/stopwords-en-v1.txt";

    private TextRules() {
    }
}
