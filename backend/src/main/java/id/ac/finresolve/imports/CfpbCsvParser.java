package id.ac.finresolve.imports;

import id.ac.finresolve.cases.ComplaintInput;
import id.ac.finresolve.cases.InputRules;
import id.ac.finresolve.categories.CategoryValues;
import id.ac.finresolve.http.BusinessException;
import id.ac.finresolve.http.BusinessException.Problem;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.commons.csv.DuplicateHeaderMode;

/**
 * Strict CFPB CSV parser. It materializes bounded records and never writes.
 *
 * <p>File-level failures (byte limit, record limit, invalid UTF-8, missing/duplicate headers, a
 * broken quote, or a header-only file) abort the whole import. Record-level failures become safe
 * {@link ImportRowProblem} values, capped at 20, and leave the rest of the file importable.
 */
public final class CfpbCsvParser {

    static final int MAX_FILE_BYTES = 20 * 1024 * 1024;
    static final int MAX_RECORDS = 5_000;
    static final int MAX_REPORTED_ERRORS = 20;
    static final int MAX_NARRATIVE_CODE_POINTS = 50_000;

    static final String COMPLAINT_ID = "Complaint ID";
    static final String PRODUCT = "Product";
    static final String SUB_PRODUCT = "Sub-product";
    static final String ISSUE = "Issue";
    static final String SUB_ISSUE = "Sub-issue";
    static final String NARRATIVE = "Consumer complaint narrative";
    static final String COMPANY = "Company";
    static final String COMPANY_RESPONSE = "Company response to consumer";
    static final String RECEIVED_DATE = "Date received";

    private static final List<String> REQUIRED_HEADERS =
            List.of(COMPLAINT_ID, PRODUCT, ISSUE, NARRATIVE);

    private static final Map<String, String> FIELD_TO_COLUMN = fieldToColumn();
    private static final DateTimeFormatter US_DATE =
            DateTimeFormatter.ofPattern("MM/dd/uuuu").withResolverStyle(ResolverStyle.STRICT);

    public ParsedImport parse(InputStream input, ImportMetadataValue metadata) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(metadata, "metadata");

        byte[] original = readBounded(input);
        String csvSha256 = sha256(original);
        String text = decode(original);

        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .setDuplicateHeaderMode(DuplicateHeaderMode.DISALLOW)
                .setAllowMissingColumnNames(false)
                .setIgnoreEmptyLines(false)
                .build();

        try (CSVParser parser = CSVParser.parse(new StringReader(text), format)) {
            Map<String, Integer> headers = parser.getHeaderMap();
            requireHeaders(headers);

            List<ParsedImport.Row> rows = new ArrayList<>();
            List<ImportRowProblem> errors = new ArrayList<>();
            int processed = 0;
            int rejected = 0;
            for (CSVRecord record : parser) {
                processed++;
                if (processed > MAX_RECORDS) {
                    throw limitExceeded();
                }
                if (record.size() > headers.size()) {
                    throw invalidCsv("Jumlah kolom pada record melebihi jumlah header.");
                }

                RowOutcome outcome = validateRecord(record, headers, processed, metadata);
                if (outcome.problem() == null) {
                    rows.add(outcome.row());
                } else {
                    rejected++;
                    if (errors.size() < MAX_REPORTED_ERRORS) {
                        errors.add(outcome.problem());
                    }
                }
            }

            if (processed == 0) {
                throw invalidCsv("Berkas CSV hanya berisi header.");
            }
            return new ParsedImport(csvSha256, processed, rows, errors, rejected);
        } catch (BusinessException failure) {
            throw failure;
        } catch (IOException | RuntimeException failure) {
            throw invalidCsv("Berkas CSV tidak dapat dibaca.");
        }
    }

    private static RowOutcome validateRecord(
            CSVRecord record,
            Map<String, Integer> headers,
            int recordNumber,
            ImportMetadataValue metadata) {
        String complaintId = trimToNull(value(record, headers, COMPLAINT_ID));
        if (complaintId == null) {
            return failure(recordNumber, COMPLAINT_ID, "REQUIRED", "ID pengaduan wajib diisi.");
        }
        BigInteger numericId;
        if (!complaintId.matches("[0-9]+")) {
            return failure(
                    recordNumber,
                    COMPLAINT_ID,
                    "INVALID_FORMAT",
                    "ID pengaduan harus bilangan bulat positif.");
        }
        numericId = new BigInteger(complaintId);
        if (numericId.signum() <= 0) {
            return failure(
                    recordNumber,
                    COMPLAINT_ID,
                    "INVALID_FORMAT",
                    "ID pengaduan harus bilangan bulat positif.");
        }

        String rawDate = trimToNull(value(record, headers, RECEIVED_DATE));
        LocalDate receivedDate;
        if (rawDate == null) {
            receivedDate = null;
        } else {
            receivedDate = parseDate(rawDate);
            if (receivedDate == null) {
                return failure(
                        recordNumber,
                        RECEIVED_DATE,
                        "INVALID_FORMAT",
                        "Tanggal diterima harus YYYY-MM-DD atau MM/DD/YYYY.");
            }
            if (receivedDate.isBefore(metadata.periodStart())
                    || receivedDate.isAfter(metadata.periodEnd())) {
                return failure(
                        recordNumber,
                        RECEIVED_DATE,
                        "OUT_OF_RANGE",
                        "Tanggal diterima berada di luar periode impor.");
            }
        }

        String narrative = value(record, headers, NARRATIVE);
        ComplaintInput complaint;
        try {
            complaint = InputRules.complaintForImport(new ComplaintInput(
                    new CategoryValues(
                            value(record, headers, PRODUCT),
                            value(record, headers, SUB_PRODUCT),
                            value(record, headers, ISSUE),
                            value(record, headers, SUB_ISSUE)),
                    narrative,
                    receivedDate,
                    value(record, headers, COMPANY),
                    value(record, headers, COMPANY_RESPONSE)));
        } catch (BusinessException failure) {
            Problem problem = failure.fieldErrors().isEmpty()
                    ? new Problem("record", "INVALID_VALUE", "Record tidak valid.")
                    : failure.fieldErrors().get(0);
            return failure(
                    recordNumber,
                    column(problem.field()),
                    rowCode(problem.code()),
                    problem.message());
        }

        if (narrative != null && codePointLength(narrative) > MAX_NARRATIVE_CODE_POINTS) {
            return failure(
                    recordNumber,
                    NARRATIVE,
                    "TOO_LONG",
                    "Narasi maksimal 50.000 karakter.");
        }

        return new RowOutcome(
                new ParsedImport.Row(recordNumber, complaintId, complaint), null);
    }

    private static LocalDate parseDate(String value) {
        try {
            return LocalDate.parse(value, DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (DateTimeParseException ignored) {
            try {
                return LocalDate.parse(value, US_DATE);
            } catch (DateTimeParseException failure) {
                return null;
            }
        }
    }

    private static void requireHeaders(Map<String, Integer> headers) {
        if (headers == null) {
            throw invalidCsv("Header CSV tidak ditemukan.");
        }
        for (String required : REQUIRED_HEADERS) {
            if (!headers.containsKey(required)) {
                throw invalidCsv("Header wajib tidak ditemukan: " + required + ".");
            }
        }
    }

    private static String value(CSVRecord record, Map<String, Integer> headers, String header) {
        Integer index = headers.get(header);
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

    private static byte[] readBounded(InputStream input) {
        try {
            byte[] bytes = input.readNBytes(MAX_FILE_BYTES + 1);
            if (bytes.length > MAX_FILE_BYTES) {
                throw new BusinessException(
                        413,
                        "PAYLOAD_TOO_LARGE",
                        "Berkas CSV melebihi batas 20 MiB.",
                        List.of());
            }
            return bytes;
        } catch (IOException failure) {
            throw new BusinessException(
                    400,
                    "INVALID_CSV",
                    "Berkas CSV tidak dapat dibaca.",
                    List.of());
        }
    }

    private static String decode(byte[] bytes) {
        int offset = hasUtf8Bom(bytes) ? 3 : 0;
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            CharBuffer decoded = decoder.decode(
                    ByteBuffer.wrap(bytes, offset, bytes.length - offset));
            return decoded.toString();
        } catch (CharacterCodingException failure) {
            throw invalidCsv("Berkas CSV harus menggunakan UTF-8 yang valid.");
        }
    }

    private static boolean hasUtf8Bom(byte[] bytes) {
        return bytes.length >= 3
                && bytes[0] == (byte) 0xEF
                && bytes[1] == (byte) 0xBB
                && bytes[2] == (byte) 0xBF;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is not available", failure);
        }
    }

    private static int codePointLength(String value) {
        return value.codePointCount(0, value.length());
    }

    private static RowOutcome failure(int recordNumber, String field, String code, String message) {
        return new RowOutcome(null, new ImportRowProblem(recordNumber, field, code, message));
    }

    private static String column(String field) {
        String column = FIELD_TO_COLUMN.get(field);
        return column == null ? "record" : column;
    }

    private static String rowCode(String code) {
        return switch (code) {
            case "REQUIRED" -> "REQUIRED";
            case "TOO_LONG" -> "TOO_LONG";
            case "NO_MEANINGFUL_TOKENS", "INVALID_FORMAT" -> "INVALID_FORMAT";
            default -> "INVALID_FORMAT";
        };
    }

    private static Map<String, String> fieldToColumn() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("product", PRODUCT);
        fields.put("subProduct", SUB_PRODUCT);
        fields.put("issue", ISSUE);
        fields.put("subIssue", SUB_ISSUE);
        fields.put("narrative", NARRATIVE);
        fields.put("company", COMPANY);
        fields.put("companyResponse", COMPANY_RESPONSE);
        fields.put("categories", PRODUCT);
        return Map.copyOf(fields);
    }

    private static BusinessException invalidCsv(String message) {
        return new BusinessException(400, "INVALID_CSV", message, List.of());
    }

    private static BusinessException limitExceeded() {
        return new BusinessException(
                400,
                "IMPORT_LIMIT_EXCEEDED",
                "Berkas CSV memuat lebih dari 5.000 record.",
                List.of());
    }

    private record RowOutcome(ParsedImport.Row row, ImportRowProblem problem) {
    }
}
