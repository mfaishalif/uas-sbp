package id.ac.finresolve.imports;

import id.ac.finresolve.http.BusinessException;
import id.ac.finresolve.http.BusinessException.Problem;
import java.net.URI;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Validated, trimmed CFPB import metadata.
 *
 * <p>Normalization is part of construction so the replay key always uses the same values the
 * report and provenance use. The archive URL is only recorded; this class never dereferences it.
 */
public record ImportMetadataValue(
        String archiveUrl, String sourceFileName, LocalDate periodStart, LocalDate periodEnd) {

    private static final int MAX_URL_CODE_POINTS = 2_048;
    private static final int MAX_FILE_NAME_CODE_POINTS = 255;

    public ImportMetadataValue {
        archiveUrl = trim(archiveUrl);
        sourceFileName = trim(sourceFileName);

        List<Problem> problems = new ArrayList<>();
        validateArchiveUrl(archiveUrl, problems);
        validateSourceFileName(sourceFileName, problems);
        if (periodStart == null) {
            problems.add(new Problem(
                    "metadata.periodStart", "REQUIRED", "Periode mulai wajib diisi."));
        }
        if (periodEnd == null) {
            problems.add(new Problem("metadata.periodEnd", "REQUIRED", "Periode akhir wajib diisi."));
        }
        if (periodStart != null && periodEnd != null && periodEnd.isBefore(periodStart)) {
            problems.add(new Problem(
                    "metadata.periodEnd",
                    "OUT_OF_RANGE",
                    "Periode akhir tidak boleh sebelum periode mulai."));
        }
        if (!problems.isEmpty()) {
            throw new BusinessException(
                    400,
                    "VALIDATION_ERROR",
                    "Metadata impor tidak valid.",
                    problems);
        }
    }

    private static void validateArchiveUrl(String value, List<Problem> problems) {
        if (value == null || value.isEmpty()) {
            problems.add(new Problem(
                    "metadata.archiveUrl", "REQUIRED", "URL arsip wajib diisi."));
            return;
        }
        if (codePointLength(value) > MAX_URL_CODE_POINTS) {
            problems.add(new Problem(
                    "metadata.archiveUrl",
                    "TOO_LONG",
                    "URL arsip maksimal 2.048 karakter."));
            return;
        }
        if (!value.startsWith("https://")) {
            problems.add(new Problem(
                    "metadata.archiveUrl",
                    "INVALID_FORMAT",
                    "URL arsip harus menggunakan HTTPS."));
            return;
        }
        try {
            URI uri = URI.create(value);
            if (uri.getHost() == null || uri.getHost().isBlank()) {
                problems.add(new Problem(
                        "metadata.archiveUrl", "INVALID_FORMAT", "URL arsip tidak valid."));
            }
        } catch (IllegalArgumentException failure) {
            problems.add(new Problem(
                    "metadata.archiveUrl", "INVALID_FORMAT", "URL arsip tidak valid."));
        }
    }

    private static void validateSourceFileName(String value, List<Problem> problems) {
        if (value == null || value.isEmpty()) {
            problems.add(new Problem(
                    "metadata.sourceFileName", "REQUIRED", "Nama berkas sumber wajib diisi."));
            return;
        }
        if (codePointLength(value) > MAX_FILE_NAME_CODE_POINTS) {
            problems.add(new Problem(
                    "metadata.sourceFileName",
                    "TOO_LONG",
                    "Nama berkas sumber maksimal 255 karakter."));
        }
        if (value.indexOf('/') >= 0 || value.indexOf('\\') >= 0) {
            problems.add(new Problem(
                    "metadata.sourceFileName",
                    "INVALID_FORMAT",
                    "Nama berkas sumber tidak boleh memuat path."));
        }
    }

    private static String trim(String value) {
        return value == null ? null : value.strip();
    }

    private static int codePointLength(String value) {
        return value.codePointCount(0, value.length());
    }
}
