package id.ac.finresolve.imports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.ac.finresolve.cases.ComplaintInput;
import id.ac.finresolve.http.BusinessException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class CfpbCsvParserTest {

    private static final String HEADER =
            "Complaint ID,Product,Sub-product,Issue,Sub-issue,"
                    + "Consumer complaint narrative,Company,Company response to consumer,Date received";
    private static final ImportMetadataValue METADATA = new ImportMetadataValue(
            "https://files.consumerfinance.gov/f/documents/synthetic-archive.zip",
            "finresolve-sample.csv",
            LocalDate.parse("2024-11-01"),
            LocalDate.parse("2024-12-31"));

    private final CfpbCsvParser parser = new CfpbCsvParser();

    @Test
    void parsesBomMultilineQuotedCommasAndBothDateFormats() {
        String csv = "\uFEFF" + HEADER + "\r\n"
                + "100,Product A,Sub A,Issue A,Sub issue A,"
                + "\"  First line\nSecond line, with comma  \",Company A,Closed,11/12/2024\r\n"
                + "101,Product A,Sub A,Issue A,,Another meaningful narrative,Company A,Closed,2024-12-01\r\n";

        ParsedImport parsed = parse(csv);

        assertThat(parsed.processed()).isEqualTo(2);
        assertThat(parsed.rejected()).isZero();
        assertThat(parsed.errors()).isEmpty();
        assertThat(parsed.rows()).hasSize(2);

        ParsedImport.Row first = parsed.rows().get(0);
        assertThat(first.recordNumber()).isEqualTo(1);
        assertThat(first.complaintId()).isEqualTo("100");
        assertThat(first.complaint().narrative())
                .isEqualTo("  First line\nSecond line, with comma  ");
        assertThat(first.complaint().receivedDate()).isEqualTo(LocalDate.parse("2024-11-12"));
        assertThat(first.complaint().categories().subProduct()).isEqualTo("Sub A");
        assertThat(first.complaint().categories().subIssue()).isEqualTo("Sub issue A");

        ParsedImport.Row second = parsed.rows().get(1);
        assertThat(second.recordNumber()).isEqualTo(2);
        assertThat(second.complaint().receivedDate()).isEqualTo(LocalDate.parse("2024-12-01"));
    }

    @Test
    void missingOptionalColumnsBecomeNull() {
        String csv = "Complaint ID,Product,Issue,Consumer complaint narrative\n"
                + "200,Product A,Issue A,A meaningful synthetic narrative\n";

        ParsedImport parsed = parse(csv);

        ComplaintInput complaint = parsed.rows().get(0).complaint();
        assertThat(complaint.receivedDate()).isNull();
        assertThat(complaint.company()).isNull();
        assertThat(complaint.companyResponse()).isNull();
        assertThat(complaint.categories().subProduct()).isNull();
        assertThat(complaint.categories().subIssue()).isNull();
    }

    @Test
    void preservesPositiveComplaintIdsLongerThanJavaScriptSafeIntegers() {
        String csv = HEADER + "\n"
                + "922337203685477580812345,Product A,Sub A,Issue A,,A meaningful narrative,"
                + "Company A,Closed,2024-11-05\n";

        ParsedImport parsed = parse(csv);

        assertThat(parsed.rows()).extracting(ParsedImport.Row::complaintId)
                .containsExactly("922337203685477580812345");
    }

    @Test
    void blankDatesAreAcceptedAndInvalidOrOutOfPeriodDatesAreRejected() {
        String csv = HEADER + "\n"
                + "300,Product A,Sub A,Issue A,,A meaningful narrative,Company A,Closed,\n"
                + "301,Product A,Sub A,Issue A,,A meaningful narrative,Company A,Closed,2024-13-01\n"
                + "302,Product A,Sub A,Issue A,,A meaningful narrative,Company A,Closed,2023-12-31\n"
                + "303,Product A,Sub A,Issue A,,A meaningful narrative,Company A,Closed,02/30/2024\n";

        ParsedImport parsed = parse(csv);

        assertThat(parsed.rows()).hasSize(1);
        assertThat(parsed.rows().get(0).complaint().receivedDate()).isNull();
        assertThat(parsed.rejected()).isEqualTo(3);
        assertThat(parsed.errors()).extracting(ImportRowProblem::recordNumber)
                .containsExactly(2, 3, 4);
        assertThat(parsed.errors()).extracting(ImportRowProblem::code)
                .containsExactly("INVALID_FORMAT", "OUT_OF_RANGE", "INVALID_FORMAT");
    }

    @Test
    void rejectsInvalidComplaintIdsRequiredFieldsAndStructuralCategories() {
        String csv = HEADER + "\n"
                + "12A,Product A,Sub A,Issue A,,A meaningful narrative,Company A,Closed,\n"
                + "0,Product A,Sub A,Issue A,,A meaningful narrative,Company A,Closed,\n"
                + "304,Product A,Sub A,,,A meaningful narrative,Company A,Closed,\n"
                + "305,Product A,Sub A,,Orphan sub-issue,Another meaningful narrative,Company A,Closed,\n";

        ParsedImport parsed = parse(csv);

        assertThat(parsed.rows()).isEmpty();
        assertThat(parsed.rejected()).isEqualTo(4);
        assertThat(parsed.errors()).extracting(ImportRowProblem::code)
                .containsExactly("INVALID_FORMAT", "INVALID_FORMAT", "REQUIRED", "REQUIRED");
    }

    @Test
    void acceptsNovelCategoriesBecauseImportNeverConsultsTheCatalog() {
        String csv = HEADER + "\n"
                + "400,Novel product,Novel sub-product,Novel issue,Novel sub-issue,"
                + "A meaningful novel narrative,Novel company,New response,2024-11-08\n";

        ParsedImport parsed = parse(csv);

        assertThat(parsed.rejected()).isZero();
        assertThat(parsed.rows()).hasSize(1);
        assertThat(parsed.rows().get(0).complaint().categories().product())
                .isEqualTo("Novel product");
    }

    @Test
    void invalidDuplicateIsRejectedBeforeDuplicateClassification() {
        String csv = HEADER + "\n"
                + "500,Product A,Sub A,Issue A,,,Company A,Closed,\n"
                + "500,Product A,Sub A,Issue A,,First valid narrative,Company A,Closed,\n"
                + "500,Product A,Sub A,Issue A,,Second valid narrative,Company A,Closed,\n";

        ParsedImport parsed = parse(csv);

        assertThat(parsed.rejected()).isEqualTo(1);
        assertThat(parsed.rows()).extracting(ParsedImport.Row::complaintId)
                .containsExactly("500", "500");
        assertThat(parsed.rows()).extracting(row -> row.complaint().narrative())
                .containsExactly("First valid narrative", "Second valid narrative");
    }

    @Test
    void capsErrorsAtTwentyButRetainsTheFullRejectedCount() {
        StringBuilder csv = new StringBuilder(HEADER).append('\n');
        for (int index = 1; index <= 21; index++) {
            csv.append(index).append(",Product A,Sub A,Issue A,,,Company A,Closed,\n");
        }

        ParsedImport parsed = parse(csv.toString());

        assertThat(parsed.processed()).isEqualTo(21);
        assertThat(parsed.rejected()).isEqualTo(21);
        assertThat(parsed.errors()).hasSize(20);
        assertThat(parsed.rows()).isEmpty();
    }

    @Test
    void rejectsNonCanonicalComplaintIdsWithLeadingZeros() {
        String csv = HEADER + "\n"
                + "00042,Product A,Sub A,Issue A,,A meaningful narrative,Company A,Closed,\n";

        ParsedImport parsed = parse(csv);

        assertThat(parsed.rows()).isEmpty();
        assertThat(parsed.rejected()).isEqualTo(1);
        assertThat(parsed.errors()).extracting(ImportRowProblem::field)
                .containsExactly("Complaint ID");
        assertThat(parsed.errors()).extracting(ImportRowProblem::code)
                .containsExactly("INVALID_FORMAT");
    }

    @Test
    void rawNarrativeLongerThanFiftyThousandCodePointsIsRejected() {
        String overlong = " " + "a".repeat(50_000) + " ";
        String csv = HEADER + "\n"
                + "600,Product A,Sub A,Issue A,,\"" + overlong + "\",Company A,Closed,\n";

        ParsedImport parsed = parse(csv);

        assertThat(parsed.rows()).isEmpty();
        assertThat(parsed.rejected()).isEqualTo(1);
        assertThat(parsed.errors()).extracting(ImportRowProblem::code)
                .containsExactly("TOO_LONG");
        assertThat(parsed.errors().get(0).field())
                .isEqualTo("Consumer complaint narrative");
    }

    @Test
    void acceptsNarrativeAtTheLimit() {
        String atLimit = "a".repeat(50_000);
        String csv = HEADER + "\n"
                + "601,Product A,Sub A,Issue A,,\"" + atLimit + "\",Company A,Closed,\n";

        ParsedImport parsed = parse(csv);

        assertThat(parsed.rejected()).isZero();
        assertThat(parsed.rows()).hasSize(1);
        assertThat(parsed.rows().get(0).complaint().narrative()).hasSize(50_000);
    }

    @Test
    void rejectsHeaderOnlyMissingAndDuplicateHeaders() {
        assertInvalidCsv(HEADER + "\n");
        assertInvalidCsv(HEADER.replace("Issue,", "") + "\n"
                + "700,Product A,Sub A,A meaningful narrative,Company A,Closed\n");
        assertInvalidCsv("Complaint ID,Complaint ID,Product,Issue,Consumer complaint narrative\n"
                + "700,700,Product A,Issue A,A meaningful narrative\n");
    }

    @Test
    void rejectsBrokenQuoteAtEofAndInvalidUtf8() throws Exception {
        assertInvalidCsv(HEADER + "\n"
                + "701,Product A,Sub A,Issue A,,\"unterminated,Company A,Closed,\n");

        byte[] header = (HEADER + "\n").getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream invalid = new ByteArrayOutputStream();
        invalid.write(header);
        invalid.write("702,Product A,Sub A,Issue A,,A meaningful narrative,Company A,Closed,"
                .getBytes(StandardCharsets.UTF_8));
        invalid.write(new byte[] {(byte) 0xC3, (byte) 0x28});
        invalid.write('\n');

        assertThatThrownBy(() -> parser.parse(
                        new ByteArrayInputStream(invalid.toByteArray()), METADATA))
                .isInstanceOfSatisfying(BusinessException.class, failure -> {
                    assertThat(failure.status()).isEqualTo(400);
                    assertThat(failure.code()).isEqualTo("INVALID_CSV");
                });
    }

    @Test
    void rejectsMoreThanFiveThousandDataRecords() {
        StringBuilder csv = new StringBuilder(HEADER).append('\n');
        for (int index = 1; index <= 5_001; index++) {
            csv.append(index)
                    .append(",Product A,Sub A,Issue A,,A meaningful narrative,Company A,Closed,\n");
        }

        assertThatThrownBy(() -> parse(csv.toString()))
                .isInstanceOfSatisfying(BusinessException.class, failure -> {
                    assertThat(failure.status()).isEqualTo(400);
                    assertThat(failure.code()).isEqualTo("IMPORT_LIMIT_EXCEEDED");
                });
    }

    @Test
    void rejectsAFileLargerThanTwentyMebibytes() {
        byte[] oversized = new byte[20 * 1024 * 1024 + 1];

        assertThatThrownBy(() -> parser.parse(new ByteArrayInputStream(oversized), METADATA))
                .isInstanceOfSatisfying(BusinessException.class, failure -> {
                    assertThat(failure.status()).isEqualTo(413);
                    assertThat(failure.code()).isEqualTo("PAYLOAD_TOO_LARGE");
                });
    }

    private ParsedImport parse(String csv) {
        return parser.parse(
                new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)), METADATA);
    }

    private void assertInvalidCsv(String csv) {
        assertThatThrownBy(() -> parse(csv))
                .isInstanceOfSatisfying(BusinessException.class, failure -> {
                    assertThat(failure.status()).isEqualTo(400);
                    assertThat(failure.code()).isEqualTo("INVALID_CSV");
                });
    }
}
