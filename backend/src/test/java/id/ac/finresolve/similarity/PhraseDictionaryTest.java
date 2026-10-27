package id.ac.finresolve.similarity;

import static org.assertj.core.api.Assertions.assertThat;

import id.ac.finresolve.cases.CaseRecord;
import id.ac.finresolve.categories.CategoryValues;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PhraseDictionaryTest {

    private final PhraseDictionary dictionary = PhraseDictionary.defaultDictionary();

    @Test
    void replacesEveryInitialVariantWithItsCanonicalToken() {
        assertThat(dictionary.normalize(
                        "ATM, cash machine, and automated teller machine"))
                .isEqualTo("atm, atm, and atm");
        assertThat(dictionary.normalize(
                        "charged twice, charged double, double charged, "
                                + "duplicate charge, duplicate charges"))
                .isEqualTo("duplicatecharge, duplicatecharge, duplicatecharge, "
                        + "duplicatecharge, duplicatecharge");
        assertThat(dictionary.normalize(
                        "funds held, held funds, funds withheld, withheld funds, "
                                + "balance held, held balance, balance withheld, withheld balance"))
                .isEqualTo("heldfunds, heldfunds, heldfunds, heldfunds, "
                        + "heldfunds, heldfunds, heldfunds, heldfunds");
    }

    @Test
    void matchesCaseInsensitivelyAndOnlyAtWordBoundaries() {
        assertThat(dictionary.normalize("CASH MACHINE")).isEqualTo("atm");
        assertThat(dictionary.normalize("ATMs format cash machines charged twices"))
                .isEqualTo("ATMs format cash machines charged twices");
        assertThat(dictionary.normalize("charged twice today")).isEqualTo(
                "duplicatecharge today");
        assertThat(dictionary.normalize("precharged twice")).isEqualTo("precharged twice");
    }

    @Test
    void neverMatchesAcrossSentencePunctuationAndIsIdempotent() {
        assertThat(dictionary.normalize("funds. Held")).isEqualTo("funds. Held");
        assertThat(dictionary.normalize("funds! held")).isEqualTo("funds! held");
        assertThat(dictionary.normalize("funds? held")).isEqualTo("funds? held");
        assertThat(dictionary.normalize("funds held")).isEqualTo("heldfunds");
        assertThat(dictionary.normalize(dictionary.normalize("funds held")))
                .isEqualTo("heldfunds");
        assertThat(dictionary.normalize("balance\nheld")).isEqualTo("heldfunds");
    }

    @Test
    void ordersVariantsLongestFirst() {
        List<String> variants = dictionary.orderedVariants();

        assertThat(variants.indexOf("automated teller machine"))
                .isLessThan(variants.indexOf("cash machine"));
        assertThat(variants.indexOf("cash machine")).isLessThan(variants.indexOf("ATM"));
        assertThat(variants.indexOf("duplicate charges"))
                .isLessThan(variants.indexOf("duplicate charge"));
        assertThat(variants.indexOf("duplicate charge"))
                .isLessThan(variants.indexOf("double charged"));
    }

    @Test
    void keepsTheOriginalCaseNarrativeUntouched() {
        CaseRecord record = new CaseRecord(
                UUID.randomUUID(),
                CaseRecord.Source.CFPB,
                new CategoryValues("P", null, "I", null),
                "Cash machine charged twice and funds held.",
                null,
                null,
                null,
                Instant.EPOCH,
                Instant.EPOCH,
                null,
                null,
                List.of());

        TfidfSnapshot snapshot = TfidfSnapshot.fit(
                List.of(record), new EnglishTokenizer(SimilarityMode.PHRASES));

        assertThat(record.narrative())
                .isEqualTo("Cash machine charged twice and funds held.");
        assertThat(snapshot.compare(
                        "ATM duplicate charge held balance", record.id())
                .score())
                .isEqualTo(1.0);
    }

    @Test
    void pinsTheDictionaryVersionAndChecksum() {
        assertThat(dictionary.version()).isEqualTo("domain-phrases-v1");
        assertThat(dictionary.sha256())
                .isEqualTo("60b1ad61268f5541a7cbeb40b48efb9fdcaae07afa611188d0d1ad192de726ec");
    }
}
