package id.ac.finresolve.sample;

import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Downloads the one approved CFPB archive and prepares the committed demo sample.
 *
 * <p>Tests never call {@link #main}; they prepare from synthetic ZIP archives. The downloaded
 * archive stays in an ignored Maven target directory and is never committed.
 */
public final class PrepareSample {

    static final String SOURCE_URL =
            "https://files.consumerfinance.gov/f/documents/"
                    + "CCDB_Export_8_November_2024_through_December_2024.zip";
    static final LocalDate PERIOD_START = LocalDate.parse("2024-11-01");
    static final LocalDate PERIOD_END = LocalDate.parse("2024-12-31");
    static final String SAMPLE_FILE = "sample.csv";
    static final String MANIFEST_FILE = "manifest.json";

    private static final JsonMapper JSON = JsonMapper.builder()
            .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

    private PrepareSample() {
    }

    public static void main(String[] args) {
        try {
            run(args);
        } catch (Exception failure) {
            String message = failure.getMessage();
            System.err.println("prepare-sample: "
                    + (message == null || message.isBlank()
                            ? failure.getClass().getSimpleName()
                            : message));
            System.exit(1);
        }
    }

    private static void run(String[] args) throws Exception {
        Path outputDirectory = parseOutputDirectory(args);
        Path workDirectory = Files.createTempDirectory(Path.of("target"), "prepare-sample-");
        Path archive = workDirectory.resolve(sourceArchiveName());
        try {
            download(archive);
            SampleManifest manifest = prepare(archive, outputDirectory, Instant.now());
            System.out.println("prepared " + manifest.selectedCount()
                    + " records from " + manifest.entryName()
                    + " into " + outputDirectory.toAbsolutePath().normalize());
        } finally {
            Files.deleteIfExists(archive);
            Files.deleteIfExists(workDirectory);
        }
    }

    private static Path parseOutputDirectory(String[] args) {
        if (args == null || args.length != 2 || !"--output-dir".equals(args[0])) {
            throw new IllegalArgumentException(
                    "usage: prepare-sample --output-dir <directory>");
        }
        if (args[1].isBlank()) {
            throw new IllegalArgumentException("--output-dir must not be blank");
        }
        return Path.of(args[1]);
    }

    /**
     * Prepares artifacts from an already downloaded archive. Package-private so tests can use a
     * synthetic archive without touching the network.
     */
    static SampleManifest prepare(Path archive, Path outputDirectory, Instant retrievedAt)
            throws IOException {
        String archiveSha256 = sha256(archive);
        Files.createDirectories(outputDirectory);

        Path temporarySample = Files.createTempFile(outputDirectory, ".sample-", ".tmp");
        Path temporaryManifest = Files.createTempFile(outputDirectory, ".manifest-", ".tmp");
        try {
            SampleSelector.Selection selection;
            String entryName;
            try (ZipFile zip = new ZipFile(archive.toFile())) {
                ZipEntry entry = csvEntry(zip)
                        .orElseThrow(() -> new IllegalStateException(
                                "archive contains no CSV entry"));
                entryName = entry.getName();
                try (InputStream csv = zip.getInputStream(entry);
                        Writer sample = Files.newBufferedWriter(
                                temporarySample, StandardCharsets.UTF_8)) {
                    selection = new SampleSelector().select(csv, sample);
                }
            }

            if (selection.selected() == 0) {
                throw new IllegalStateException(
                        "no eligible records matched the fixed demo filters");
            }

            String sampleSha256 = sha256(temporarySample);
            SampleManifest manifest = new SampleManifest(
                    SOURCE_URL,
                    archive.getFileName().toString(),
                    entryName,
                    PERIOD_START,
                    PERIOD_END,
                    SampleSelector.PRODUCTS,
                    true,
                    true,
                    SampleSelector.LIMIT,
                    retrievedAt,
                    archiveSha256,
                    sampleSha256,
                    selection.selected(),
                    selection.selectedIds());
            String manifestJson = JSON.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(manifest)
                    .replace("\r\n", "\n")
                    + "\n";
            Files.writeString(temporaryManifest, manifestJson, StandardCharsets.UTF_8);

            replace(temporarySample, outputDirectory.resolve(SAMPLE_FILE));
            replace(temporaryManifest, outputDirectory.resolve(MANIFEST_FILE));
            return manifest;
        } finally {
            Files.deleteIfExists(temporarySample);
            Files.deleteIfExists(temporaryManifest);
        }
    }

    private static Optional<ZipEntry> csvEntry(ZipFile zip) {
        return zip.stream()
                .filter(entry -> !entry.isDirectory())
                .filter(entry -> entry.getName().toLowerCase(java.util.Locale.ROOT)
                        .endsWith(".csv"))
                .map(entry -> (ZipEntry) entry)
                .findFirst();
    }

    private static void download(Path archive) throws IOException, InterruptedException {
        Files.deleteIfExists(archive);
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        // The archive CDN rejects unlisted User-Agent tokens with HTTP 403. Use the same
        // widely accepted downloader token as the documented walkthrough's curl fallback.
        HttpRequest request = HttpRequest.newBuilder(URI.create(SOURCE_URL))
                .timeout(Duration.ofMinutes(10))
                .header("User-Agent", "curl/8.10.1 (FinResolve-Sample-Preparer/0.1)")
                .header("Accept", "application/zip")
                .GET()
                .build();
        HttpResponse<Path> response =
                client.send(request, HttpResponse.BodyHandlers.ofFile(archive));
        if (response.statusCode() != 200) {
            Files.deleteIfExists(archive);
            throw new IOException(
                    "archive download returned HTTP " + response.statusCode());
        }
    }

    private static String sourceArchiveName() {
        String path = URI.create(SOURCE_URL).getPath();
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private static void replace(Path temporary, Path target) throws IOException {
        try {
            Files.move(
                    temporary,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException failure) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String sha256(Path file) throws IOException {
        try (InputStream input = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is not available", failure);
        }
    }
}
