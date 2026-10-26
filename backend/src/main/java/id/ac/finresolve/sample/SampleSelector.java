package id.ac.finresolve.sample;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PushbackInputStream;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;
import org.apache.commons.csv.DuplicateHeaderMode;

/**
 * Selects the first eligible CFPB records from one source CSV.
 *
 * <p>Eligibility is exactly the documented demo filter: a nonblank narrative, one of the two
 * approved products, a nonblank issue, and the first occurrence of a unique complaint ID. The
 * selector streams records and writes only the nine application columns, preserving the source
 * narrative text.
 */
public final class SampleSelector {

    static final int LIMIT = 500;
    static final List<String> PRODUCTS = List.of(
            "Checking or savings account",
            "Money transfer, virtual currency, or money service");
    static final List<String> OUTPUT_HEADERS = List.of(
            "Complaint ID",
            "Product",
            "Sub-product",
            "Issue",
            "Sub-issue",
            "Consumer complaint narrative",
            "Company",
            "Company response to consumer",
            "Date received");

    private static final String COMPLAINT_ID = "Complaint ID";
    private static final String PRODUCT = "Product";
    private static final String SUB_PRODUCT = "Sub-product";
    private static final String ISSUE = "Issue";
    private static final String SUB_ISSUE = "Sub-issue";
    private static final String NARRATIVE = "Consumer complaint narrative";
    private static final String COMPANY = "Company";
    private static final String COMPANY_RESPONSE = "Company response to consumer";
    private static final String RECEIVED_DATE = "Date received";

    public Selection select(InputStream csv, Writer sample) throws IOException {
        Objects.requireNonNull(csv, "csv");
        Objects.requireNonNull(sample, "sample");

        PushbackInputStream bounded = new PushbackInputStream(csv, 3);
        byte[] prefix = bounded.readNBytes(3);
        if (!Arrays.equals(prefix, new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF})) {
            bounded.unread(prefix);
        }

        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try (Reader reader = new InputStreamReader(bounded, decoder);
                CSVParser parser = CSVParser.parse(reader, sourceFormat());
                CSVPrinter printer = new CSVPrinter(
                        sample,
                        CSVFormat.DEFAULT.builder()
                                .setRecordSeparator("\n")
                                .build())) {
            Map<String, Integer> headers = new LinkedHashMap<>(parser.getHeaderMap());
            requireHeaders(headers);
            printer.printRecord(OUTPUT_HEADERS);

            List<String> selectedIds = new ArrayList<>();
            Set<String> seenIds = new HashSet<>();
            int scanned = 0;
            for (CSVRecord record : parser) {
                if (selectedIds.size() >= LIMIT) {
                    break;
                }
                scanned++;

                String id = trimToNull(value(record, headers, COMPLAINT_ID));
                String product = value(record, headers, PRODUCT);
                String issue = value(record, headers, ISSUE);
                String narrative = value(record, headers, NARRATIVE);
                if (id == null
                        || !PRODUCTS.contains(product)
                        || trimToNull(issue) == null
                        || trimToNull(narrative) == null
                        || !seenIds.add(id)) {
                    continue;
                }

                selectedIds.add(id);
                printer.printRecord(
                        id,
                        product,
                        value(record, headers, SUB_PRODUCT),
                        issue,
                        value(record, headers, SUB_ISSUE),
                        narrative,
                        value(record, headers, COMPANY),
                        value(record, headers, COMPANY_RESPONSE),
                        value(record, headers, RECEIVED_DATE));
            }
            printer.flush();
            return new Selection(selectedIds, scanned, selectedIds.size());
        } catch (CharacterCodingException failure) {
            throw new IOException("Source CSV is not valid UTF-8.", failure);
        }
    }

    private static CSVFormat sourceFormat() {
        return CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .setDuplicateHeaderMode(DuplicateHeaderMode.DISALLOW)
                .setAllowMissingColumnNames(false)
                .setIgnoreEmptyLines(false)
                .build();
    }

    private static void requireHeaders(Map<String, Integer> headers) {
        List<String> missing = OUTPUT_HEADERS.stream()
                .filter(header -> !headers.containsKey(header))
                .toList();
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException(
                    "Source CSV is missing required columns: " + String.join(", ", missing));
        }
    }

    private static String value(CSVRecord record, Map<String, Integer> headers, String name) {
        Integer index = headers.get(name);
        if (index == null || index >= record.size()) {
            return null;
        }
        return record.get(index);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public record Selection(List<String> selectedIds, int scanned, int selected) {

        public Selection {
            selectedIds = List.copyOf(selectedIds == null ? List.of() : selectedIds);
        }
    }
}
