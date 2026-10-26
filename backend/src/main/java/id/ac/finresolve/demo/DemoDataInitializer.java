package id.ac.finresolve.demo;

import id.ac.finresolve.imports.BatchRecord;
import id.ac.finresolve.imports.CfpbCsvParser;
import id.ac.finresolve.imports.ImportMetadataValue;
import id.ac.finresolve.imports.ImportService;
import id.ac.finresolve.imports.ParsedImport;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Opt-in startup import of the committed CFPB sample.
 *
 * <p>Migrations have already run when an {@link ApplicationRunner} executes. The existing import
 * service supplies the transaction and replay behavior, so a restart reuses the recorded batch
 * instead of duplicating cases. Any failure aborts startup rather than serving an empty demo.
 */
@Component
public final class DemoDataInitializer implements ApplicationRunner {

    static final int SAMPLE_RECORDS = 500;

    static final ImportMetadataValue SAMPLE_METADATA = new ImportMetadataValue(
            "https://files.consumerfinance.gov/f/documents/"
                    + "CCDB_Export_8_November_2024_through_December_2024.zip",
            "finresolve-sample.csv",
            LocalDate.parse("2024-11-01"),
            LocalDate.parse("2024-12-31"));

    private static final CfpbCsvParser PARSER = new CfpbCsvParser();
    private static final Logger LOG = LoggerFactory.getLogger(DemoDataInitializer.class);

    private final ImportService imports;
    private final DemoReadiness readiness;
    private final boolean enabled;
    private final Path samplePath;

    @Autowired
    public DemoDataInitializer(
            ImportService imports,
            DemoReadiness readiness,
            @Value("${finresolve.demo.enabled:false}") boolean enabled,
            @Value("${finresolve.demo.sample-path:data/cfpb/sample.csv}") String samplePath) {
        this(imports, readiness, enabled, Path.of(samplePath));
    }

    DemoDataInitializer(
            ImportService imports, DemoReadiness readiness, boolean enabled, Path samplePath) {
        this.imports = imports;
        this.readiness = readiness;
        this.enabled = enabled;
        this.samplePath = samplePath;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        initialize();
    }

    /**
     * Imports the sample when enabled and returns its report. A disabled initializer returns
     * {@code null} without touching the filesystem.
     *
     * <p>The sample is parsed and checked before the import transaction starts, so an invalid or
     * wrong-sized file cannot leave a partially populated demo database behind.
     */
    ImportService.Result initialize() {
        if (!enabled) {
            return null;
        }

        Path absolute = samplePath.toAbsolutePath().normalize();
        byte[] sample;
        try {
            sample = Files.readAllBytes(absolute);
        } catch (IOException failure) {
            throw new IllegalStateException(
                    "Demo sample cannot be read: " + absolute, failure);
        }

        ParsedImport parsed;
        try {
            parsed = PARSER.parse(new ByteArrayInputStream(sample), SAMPLE_METADATA);
        } catch (RuntimeException failure) {
            throw importFailed(absolute, failure);
        }
        requireExpectedShape(parsed.processed(), parsed.rejected());

        ImportService.Result result;
        try {
            result = imports.importCsv(new ByteArrayInputStream(sample), SAMPLE_METADATA);
        } catch (RuntimeException failure) {
            throw importFailed(absolute, failure);
        }

        BatchRecord batch = result.batch();
        requireExpectedShape(batch.processed(), batch.rejected());

        readiness.markReady();
        LOG.info(
                "Demo sample ready: {} records imported, {} duplicates, {} rejected, replayed={}",
                batch.imported(),
                batch.duplicates(),
                batch.rejected(),
                result.replayed());
        return result;
    }

    private static IllegalStateException importFailed(Path sample, RuntimeException failure) {
        return new IllegalStateException(
                "Demo sample import failed: " + sample + " (" + failure.getMessage() + ")",
                failure);
    }

    private static void requireExpectedShape(int processed, int rejected) {
        if (processed != SAMPLE_RECORDS || rejected != 0) {
            throw new IllegalStateException(
                    "Demo sample must contain exactly " + SAMPLE_RECORDS
                            + " valid records; processed=" + processed
                            + ", rejected=" + rejected
                            + ". Use the committed data/cfpb/sample.csv or reset the demo volume.");
        }
    }
}
