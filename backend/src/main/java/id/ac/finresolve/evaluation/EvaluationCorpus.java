package id.ac.finresolve.evaluation;

import id.ac.finresolve.cases.CaseRecord;
import id.ac.finresolve.cases.ComplaintInput;
import id.ac.finresolve.imports.CfpbCsvParser;
import id.ac.finresolve.imports.ImportMetadataValue;
import id.ac.finresolve.imports.ParsedImport;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Offline evaluation corpus parsed from the committed CFPB sample with the production CSV parser.
 *
 * <p>No database is touched and no archive is downloaded. The sample checksum is compared with the
 * committed manifest before any scoring starts.
 */
public final class EvaluationCorpus {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final UUID BATCH_ID = UUID.nameUUIDFromBytes(
            "finresolve-evaluation-batch".getBytes(StandardCharsets.UTF_8));

    private final List<CaseRecord> cases;
    private final String sampleSha256;
    private final int sampleRecordCount;

    private EvaluationCorpus(
            List<CaseRecord> cases, String sampleSha256, int sampleRecordCount) {
        this.cases = List.copyOf(cases);
        this.sampleSha256 = sampleSha256;
        this.sampleRecordCount = sampleRecordCount;
    }

    static EvaluationCorpus fromCases(List<CaseRecord> cases) {
        return new EvaluationCorpus(cases, "test-checksum", cases.size());
    }

    public static EvaluationCorpus load(Path samplePath, Path manifestPath) {        String checksum = sha256(samplePath);
        JsonNode manifest = readManifest(manifestPath);
        String expectedChecksum = requiredText(manifest, "sampleSha256");
        if (!expectedChecksum.equals(checksum)) {
            throw new IllegalArgumentException(
                    "Sample checksum does not match the committed manifest: " + checksum);
        }

        int expectedCount = requiredInt(manifest, "selectedCount");
        ImportMetadataValue metadata = new ImportMetadataValue(
                requiredText(manifest, "sourceUrl"),
                requiredText(manifest, "archiveName"),
                LocalDate.parse(requiredText(manifest, "periodStart")),
                LocalDate.parse(requiredText(manifest, "periodEnd")));

        ParsedImport parsed;
        try (InputStream input = Files.newInputStream(samplePath)) {
            parsed = new CfpbCsvParser().parse(input, metadata);
        } catch (IOException failure) {
            throw new IllegalArgumentException("Cannot read sample: " + samplePath, failure);
        }
        if (parsed.rejected() != 0 || parsed.processed() != expectedCount) {
            throw new IllegalArgumentException(
                    "Sample shape mismatch: processed=" + parsed.processed()
                            + ", rejected=" + parsed.rejected()
                            + ", expected=" + expectedCount);
        }

        List<CaseRecord> cases = new ArrayList<>(parsed.rows().size());
        Set<UUID> ids = new HashSet<>();
        Set<String> complaintIds = new HashSet<>();
        for (ParsedImport.Row row : parsed.rows()) {
            if (!complaintIds.add(row.complaintId())) {
                throw new IllegalArgumentException(
                        "Duplicate CFPB complaint ID in sample: " + row.complaintId());
            }
            ComplaintInput complaint = row.complaint();
            UUID id = EvaluationCaseIds.fromComplaintId(row.complaintId());
            if (!ids.add(id)) {
                throw new IllegalStateException(
                        "Derived evaluation UUID collision for " + row.complaintId());
            }
            cases.add(new CaseRecord(
                    id,
                    CaseRecord.Source.CFPB,
                    complaint.categories(),
                    complaint.narrative(),
                    complaint.receivedDate(),
                    complaint.company(),
                    complaint.companyResponse(),
                    Instant.EPOCH,
                    Instant.EPOCH,
                    new CaseRecord.Provenance(
                            BATCH_ID,
                            row.complaintId(),
                            metadata.archiveUrl(),
                            metadata.sourceFileName(),
                            metadata.periodStart(),
                            metadata.periodEnd(),
                            checksum),
                    null,
                    List.of()));
        }
        return new EvaluationCorpus(cases, checksum, parsed.processed());
    }

    public List<CaseRecord> cases() {
        return cases;
    }

    public String sampleSha256() {
        return sampleSha256;
    }

    public int sampleRecordCount() {
        return sampleRecordCount;
    }

    private static JsonNode readManifest(Path path) {
        try {
            return JSON.readTree(Files.readString(path, StandardCharsets.UTF_8));
        } catch (IOException failure) {
            throw new IllegalArgumentException("Cannot read manifest: " + path, failure);
        }
    }

    private static String requiredText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asString().isBlank()) {
            throw new IllegalArgumentException("Manifest is missing text field: " + field);
        }
        return value.asString();
    }

    private static int requiredInt(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber()) {
            throw new IllegalArgumentException("Manifest is missing integer field: " + field);
        }
        return value.asInt();
    }

    static String sha256(Path path) {
        try (InputStream input = Files.newInputStream(path)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException failure) {
            throw new IllegalArgumentException("Cannot checksum sample: " + path, failure);
        }
    }
}
