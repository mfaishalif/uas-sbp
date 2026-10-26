package id.ac.finresolve.sample;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.ac.finresolve.imports.CfpbCsvParser;
import id.ac.finresolve.imports.ImportMetadataValue;
import id.ac.finresolve.imports.ParsedImport;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SampleSelectorTest {

    private static final String INPUT_HEADER =
            "Complaint ID,Product,Sub-product,Issue,Sub-issue,"
                    + "Consumer complaint narrative,Company,Company response to consumer,Date received,"
                    + "State,ZIP code,Tags,Consumer consent provided?,Submitted via,"
                    + "Date sent to company";
    private static final String OUTPUT_HEADER =
            "Complaint ID,Product,Sub-product,Issue,Sub-issue,"
                    + "Consumer complaint narrative,Company,Company response to consumer,Date received";
    private static final String CHECKING = "Checking or savings account";
    private static final String MONEY = "Money transfer, virtual currency, or money service";
    private static final String EXCLUDED_PRODUCT = "Credit card";
    private static final Instant RETRIEVED_AT = Instant.parse("2026-10-26T10:15:30Z");

    @TempDir
    Path directory;

    private final SampleSelector selector = new SampleSelector();

    @Test
    void selectsEligibleRowsInSourceOrderAndWritesOnlyNineColumns() throws Exception {
        String source = INPUT_HEADER + "\n"
                + row("300", CHECKING, "A checking narrative", "Managing an account",
                "CA", "94105", "tag-checking")
                + row("100", EXCLUDED_PRODUCT, "Excluded narrative", "Billing dispute",
                "NY", "10001", "tag-excluded")
                + row("200", MONEY, "A money transfer narrative", "Money was not available",
                "TX", "73301", "tag-money")
                + row("400", CHECKING, "", "Managing an account",
                "WA", "98101", "tag-blank-narrative")
                + row("500", CHECKING, "An issue-less narrative", "",
                "OR", "97201", "tag-blank-issue")
                + row("300", CHECKING, "A duplicate narrative", "Managing an account",
                "FL", "33101", "tag-duplicate");

        SelectionView result = select(source);

        assertThat(result.selection().selectedIds()).containsExactly("300", "200");
        assertThat(result.selection().selected()).isEqualTo(2);
        assertThat(result.selection().scanned()).isEqualTo(6);
        assertThat(result.sample()).startsWith(OUTPUT_HEADER + "\n");
        assertThat(result.sample()).doesNotContain("Excluded narrative", "tag-checking",
                "tag-excluded", "tag-money", "tag-duplicate", "94105", "State", "ZIP code",
                "Tags", "Consumer consent provided?", "Submitted via", "Date sent to company");
    }

    @Test
    void capsAtFiveHundredUniqueIdsInOriginalOrder() throws Exception {
        StringBuilder source = new StringBuilder(INPUT_HEADER).append('\n');
        for (int index = 1; index <= 600; index++) {
            source.append(row(String.valueOf(index), CHECKING,
                    "Narrative number " + index, "Issue " + index, "", "", ""));
        }

        SelectionView result = select(source.toString());

        assertThat(result.selection().selected()).isEqualTo(500);
        assertThat(result.selection().selectedIds()).hasSize(500);
        assertThat(result.selection().selectedIds().get(0)).isEqualTo("1");
        assertThat(result.selection().selectedIds().get(499)).isEqualTo("500");
    }

    @Test
    void fewerCandidatesKeepAllRowsAndScanEveryRecord() throws Exception {
        String source = INPUT_HEADER + "\n"
                + row("11", CHECKING, "First narrative", "First issue", "", "", "")
                + row("12", MONEY, "Second narrative", "Second issue", "", "", "")
                + row("13", CHECKING, "Third narrative", "Third issue", "", "", "");

        SelectionView result = select(source);

        assertThat(result.selection().selected()).isEqualTo(3);
        assertThat(result.selection().scanned()).isEqualTo(3);
        assertThat(result.selection().selectedIds()).containsExactly("11", "12", "13");
    }

    @Test
    void zeroCandidatesFailWithoutReplacingPriorArtifacts() throws Exception {
        Path archive = zip(directory.resolve("zero.zip"), "source.csv",
                INPUT_HEADER + "\n"
                        + row("1", EXCLUDED_PRODUCT, "Excluded", "Issue", "", "", ""));
        Path output = Files.createDirectory(directory.resolve("output"));
        Path previousSample = output.resolve("sample.csv");
        Path previousManifest = output.resolve("manifest.json");
        Files.writeString(previousSample, "previous sample\n", StandardCharsets.UTF_8);
        Files.writeString(previousManifest, "previous manifest\n", StandardCharsets.UTF_8);

        assertThatThrownBy(() -> PrepareSample.prepare(archive, output, RETRIEVED_AT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no eligible");

        assertThat(Files.readString(previousSample, StandardCharsets.UTF_8))
                .isEqualTo("previous sample\n");
        assertThat(Files.readString(previousManifest, StandardCharsets.UTF_8))
                .isEqualTo("previous manifest\n");
    }

    @Test
    void multilineNarrativeSurvivesReimportThroughTheTaskFiveParser() throws Exception {
        String narrative = "  First line\nSecond line, with \"quotes\" and commas  ";
        String source = INPUT_HEADER + "\n"
                + row("900", CHECKING, narrative, "Managing an account", "", "", "");

        SelectionView result = select(source);
        ImportMetadataValue metadata = new ImportMetadataValue(
                PrepareSample.SOURCE_URL,
                "sample.csv",
                PrepareSample.PERIOD_START,
                PrepareSample.PERIOD_END);
        ParsedImport parsed = new CfpbCsvParser().parse(
                new ByteArrayInputStream(result.sample().getBytes(StandardCharsets.UTF_8)),
                metadata);

        assertThat(parsed.processed()).isEqualTo(1);
        assertThat(parsed.rejected()).isZero();
        assertThat(parsed.rows()).hasSize(1);
        assertThat(parsed.rows().get(0).complaint().narrative()).isEqualTo(narrative);
        assertThat(parsed.rows().get(0).complaint().receivedDate())
                .isEqualTo(LocalDate.parse("2024-11-05"));
    }

    @Test
    void preparationIsDeterministicAndTheManifestIsTraceable() throws Exception {
        Path archive = zip(directory.resolve("CCDB_Export_synthetic.zip"), "source.csv",
                INPUT_HEADER + "\n"
                        + row("7", CHECKING, "First prepared narrative", "Issue A", "", "", "")
                        + row("8", MONEY, "Second prepared narrative", "Issue B", "", "", ""));
        Path firstOutput = Files.createDirectory(directory.resolve("first"));
        Path secondOutput = Files.createDirectory(directory.resolve("second"));

        SampleManifest first = PrepareSample.prepare(
                archive, firstOutput, RETRIEVED_AT);
        SampleManifest second = PrepareSample.prepare(
                archive, secondOutput, RETRIEVED_AT.plusSeconds(90));

        byte[] firstSample = Files.readAllBytes(firstOutput.resolve("sample.csv"));
        byte[] secondSample = Files.readAllBytes(secondOutput.resolve("sample.csv"));
        byte[] manifestBytes = Files.readAllBytes(firstOutput.resolve("manifest.json"));

        assertThat(firstSample).isEqualTo(secondSample);
        assertThat(new String(firstSample, StandardCharsets.UTF_8))
                .doesNotContain("\r");
        assertThat(new String(manifestBytes, StandardCharsets.UTF_8))
                .doesNotContain("\r")
                .endsWith("\n");
        assertThat(first.selectedIds()).containsExactly("7", "8");
        assertThat(second.selectedIds()).containsExactly("7", "8");
        assertThat(first.retrievedAt()).isEqualTo(RETRIEVED_AT);
        assertThat(second.retrievedAt()).isEqualTo(RETRIEVED_AT.plusSeconds(90));

        assertThat(first.sourceUrl()).isEqualTo(PrepareSample.SOURCE_URL);
        assertThat(first.archiveName()).isEqualTo("CCDB_Export_synthetic.zip");
        assertThat(first.entryName()).isEqualTo("source.csv");
        assertThat(first.periodStart()).isEqualTo(PrepareSample.PERIOD_START);
        assertThat(first.periodEnd()).isEqualTo(PrepareSample.PERIOD_END);
        assertThat(first.products()).containsExactly(CHECKING, MONEY);
        assertThat(first.limit()).isEqualTo(500);
        assertThat(first.selectedCount()).isEqualTo(2);
        assertThat(first.archiveSha256()).isEqualTo(sha256(archive));
        assertThat(first.sampleSha256()).isEqualTo(sha256(firstOutput.resolve("sample.csv")));
        assertThat(second.archiveSha256()).isEqualTo(first.archiveSha256());
        assertThat(second.sampleSha256()).isEqualTo(first.sampleSha256());
    }

    private SelectionView select(String source) throws IOException {
        StringWriter writer = new StringWriter();
        SampleSelector.Selection selection = selector.select(
                new ByteArrayInputStream(source.getBytes(StandardCharsets.UTF_8)), writer);
        return new SelectionView(selection, writer.toString());
    }

    private static Path zip(Path destination, String entryName, String csv) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(destination))) {
            zip.putNextEntry(new ZipEntry("readme.txt"));
            zip.write("not a CSV".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry(entryName));
            zip.write(csv.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return destination;
    }

    private static String row(
            String id,
            String product,
            String narrative,
            String issue,
            String state,
            String zip,
            String tags) {
        return String.join(",",
                quote(id),
                quote(product),
                quote("Sub"),
                quote(issue),
                quote(""),
                quote(narrative),
                quote("Company"),
                quote("Closed"),
                quote("2024-11-05"),
                quote(state),
                quote(zip),
                quote(tags),
                quote("Consent"),
                quote("Web"),
                quote("2024-11-06")) + "\n";
    }

    private static String quote(String value) {
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private static String sha256(Path file) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }

    private record SelectionView(SampleSelector.Selection selection, String sample) {
    }
}
