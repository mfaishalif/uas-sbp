package id.ac.finresolve.domain.importing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import id.ac.finresolve.domain.error.BusinessException;
import id.ac.finresolve.support.HttpStatusOf;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class ImportMetadataValueTest {

    private static final String URL = "https://example.com/archive.zip";
    private static final LocalDate START = LocalDate.parse("2024-11-01");
    private static final LocalDate END = LocalDate.parse("2024-12-31");

    @Test
    void rejectsNulAndUnpairedSurrogatesInMetadataText() {
        assertRejected("\u0000file.csv", "metadata.sourceFileName");
        assertRejected("file\u0000.csv", "metadata.sourceFileName");
        assertRejected("file.csv\u0000", "metadata.sourceFileName");
        assertRejected("file\uD800.csv", "metadata.sourceFileName");
        assertRejected("file\uDC00.csv", "metadata.sourceFileName");
        assertRejectedUrl("https://example.com/\u0000archive.zip");
        assertRejectedUrl("https://example.com/\uD800archive.zip");
    }

    @Test
    void rejectsPathSegmentAndDriveRelativeNamesAfterTrim() {
        for (String name : List.of(
                ".", "..", " . ", " .. ", "../x.csv", "a/b.csv", "a\\b.csv",
                "C:x", " C:x ", "c:file.csv")) {
            assertRejected(name, "metadata.sourceFileName");
        }
    }

    @Test
    void acceptsNormalAndUnicodeBasenamesWithoutRequiringAnExtension() {
        ImportMetadataValue metadata = new ImportMetadataValue(
                URL, "  report-😀-𝄞  ", START, END);
        assertThat(metadata.sourceFileName()).isEqualTo("report-😀-𝄞");

        ImportMetadataValue noExtension = new ImportMetadataValue(URL, " report ", START, END);
        assertThat(noExtension.sourceFileName()).isEqualTo("report");
    }

    private static void assertRejected(String sourceFileName, String field) {
        BusinessException failure = catchThrowableOfType(
                () -> new ImportMetadataValue(URL, sourceFileName, START, END),
                BusinessException.class);
        assertInvalid(failure, field);
    }

    private static void assertRejectedUrl(String archiveUrl) {
        BusinessException failure = catchThrowableOfType(
                () -> new ImportMetadataValue(archiveUrl, "file.csv", START, END),
                BusinessException.class);
        assertInvalid(failure, "metadata.archiveUrl");
    }

    private static void assertInvalid(BusinessException failure, String field) {
        assertThat(failure).isNotNull();
        assertThat(failure.code()).isEqualTo("VALIDATION_ERROR");
        assertThat(HttpStatusOf.of(failure)).isEqualTo(400);
        assertThat(failure.fieldErrors()).anySatisfy(problem -> {
            assertThat(problem.field()).isEqualTo(field);
            assertThat(problem.code()).isEqualTo("INVALID_FORMAT");
        });
    }
}
