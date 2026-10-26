package id.ac.finresolve.sample;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Provenance for the committed deterministic demo sample.
 *
 * <p>The manifest records the exact source, filters, and checksums; it does not claim that the
 * sample is representative or anonymized beyond the source data.
 */
public record SampleManifest(
        String sourceUrl,
        String archiveName,
        String entryName,
        LocalDate periodStart,
        LocalDate periodEnd,
        List<String> products,
        boolean narrativeNonEmpty,
        boolean issueNonEmpty,
        int limit,
        Instant retrievedAt,
        String archiveSha256,
        String sampleSha256,
        int selectedCount,
        List<String> selectedIds) {

    public SampleManifest {
        products = List.copyOf(products == null ? List.of() : products);
        selectedIds = List.copyOf(selectedIds == null ? List.of() : selectedIds);
    }
}
