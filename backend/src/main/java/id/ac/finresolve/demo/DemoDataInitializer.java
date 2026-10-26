package id.ac.finresolve.demo;

import id.ac.finresolve.imports.BatchRecord;
import id.ac.finresolve.imports.ImportMetadataValue;
import id.ac.finresolve.imports.ImportService;
import java.io.IOException;
import java.io.InputStream;
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
     */
    ImportService.Result initialize() {
        if (!enabled) {
            return null;
        }

        Path absolute = samplePath.toAbsolutePath().normalize();
        ImportService.Result result;
        try (InputStream input = Files.newInputStream(absolute)) {
            result = imports.importCsv(input, SAMPLE_METADATA);
        } catch (IOException failure) {
            throw new IllegalStateException(
                    "Demo sample cannot be read: " + absolute, failure);
        } catch (RuntimeException failure) {
            throw new IllegalStateException(
                    "Demo sample import failed: " + absolute + " (" + failure.getMessage() + ")",
                    failure);
        }

        BatchRecord batch = result.batch();
        if (batch.processed() != SAMPLE_RECORDS || batch.rejected() != 0) {
            throw new IllegalStateException(
                    "Demo sample must contain exactly " + SAMPLE_RECORDS
                            + " valid records; processed=" + batch.processed()
                            + ", rejected=" + batch.rejected()
                            + ". Use the committed data/cfpb/sample.csv or reset the demo volume.");
        }

        readiness.markReady();
        LOG.info(
                "Demo sample ready: {} records imported, {} duplicates, {} rejected, replayed={}",
                batch.imported(),
                batch.duplicates(),
                batch.rejected(),
                result.replayed());
        return result;
    }
}
