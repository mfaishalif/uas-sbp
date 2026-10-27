package id.ac.finresolve.evaluation;

import id.ac.finresolve.domain.cases.CaseRecord;
import id.ac.finresolve.domain.similarity.SimilarityMode;
import id.ac.finresolve.domain.similarity.SimilarityResult;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Writes the committed v2 calibration/test CSV, JSON, and Markdown artifacts. */
public final class EvaluationReportWriter {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private EvaluationReportWriter() {
    }

    public static void write(Path outputDirectory, EvaluationRunResult run) {
        try {
            Files.createDirectories(outputDirectory);
            writeCalibrationCsv(
                    outputDirectory.resolve("calibration-configurations.csv"), run);
            writePerQueryCsv(outputDirectory.resolve("per-query-results.csv"), run);
            writeLabelAudit(outputDirectory.resolve("label-audit.json"), run);
            writeSummary(outputDirectory.resolve("summary.json"), run);
            writeMarkdown(outputDirectory.resolve("report.md"), run);
        } catch (IOException failure) {
            throw new IllegalArgumentException(
                    "Cannot write evaluation artifacts to " + outputDirectory, failure);
        }
    }

    private static void writeCalibrationCsv(Path path, EvaluationRunResult run)
            throws IOException {
        EvaluationMetrics.Aggregate baseline = run.calibrationBaseline();
        try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8);
                CSVPrinter printer = new CSVPrinter(
                        writer,
                        CSVFormat.DEFAULT.builder()
                                .setHeader(
                                        "mode",
                                        "categoryWeight",
                                        "textWeight",
                                        "threshold",
                                        "meanPrecisionAt5",
                                        "macroPrecision",
                                        "microPrecision",
                                        "complaintSuccesses",
                                        "complaintSuccessRate",
                                        "complaintReturned",
                                        "relevantReturned",
                                        "negativeReturned",
                                        "totalReturned",
                                        "negativeFailures",
                                        "negativeFailureRate",
                                        "eligible",
                                        "selected")
                                .setRecordSeparator("\n")
                                .build())) {
            for (EvaluationConfig config : run.study().configs()) {
                EvaluationMetrics.Aggregate aggregate =
                        run.calibrationAggregates().get(config);
                if (aggregate == null) {
                    throw new IllegalArgumentException(
                            "Missing calibration metrics for " + config.label());
                }
                printer.printRecord(
                        config.mode().configValue(),
                        decimal(config.categoryWeight(), 1),
                        decimal(config.textWeight(), 1),
                        decimal(config.threshold(), 3),
                        aggregate.meanPrecisionAt5(),
                        aggregate.macroPrecision(),
                        aggregate.microPrecision(),
                        aggregate.complaintSuccesses(),
                        aggregate.complaintSuccessRate(),
                        aggregate.complaintReturned(),
                        aggregate.relevantReturned(),
                        aggregate.negativeReturned(),
                        aggregate.totalReturned(),
                        aggregate.negativeFailures(),
                        aggregate.negativeFailureRate(),
                        EvaluationSelection.isEligible(aggregate, baseline) ? "1" : "0",
                        config.sameAs(run.selected()) ? "1" : "0");
            }
        }
    }

    private static void writePerQueryCsv(Path path, EvaluationRunResult run)
            throws IOException {
        try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8);
                CSVPrinter printer = new CSVPrinter(
                        writer,
                        CSVFormat.DEFAULT.builder()
                                .setHeader(
                                        "split",
                                        "role",
                                        "mode",
                                        "categoryWeight",
                                        "textWeight",
                                        "threshold",
                                        "queryId",
                                        "kind",
                                        "returnedCount",
                                        "relevantCount",
                                        "precisionAt5",
                                        "returnedPrecision",
                                        "hasResults",
                                        "hasRelevant",
                                        "returnedCfpbIds")
                                .setRecordSeparator("\n")
                                .build())) {
            printPart(
                    printer, run, EvaluationSplit.Part.CALIBRATION,
                    run.calibrationLabels(),
                    run.calibrationBaselineResults(), run.calibrationSelectedResults());
            if (run.testUsed()) {
                printPart(
                        printer, run, EvaluationSplit.Part.TEST,
                        run.finalLabels(),
                        run.testBaselineResults(), run.testSelectedResults());
            }
        }
    }

    private static void printPart(
            CSVPrinter printer,
            EvaluationRunResult run,
            EvaluationSplit.Part part,
            EvaluationLabels labels,
            Map<String, List<SimilarityResult>> baselineResults,
            Map<String, List<SimilarityResult>> selectedResults) throws IOException {
        List<Role> roles = List.of(
                new Role("baseline", run.baseline(), baselineResults),
                new Role("selected", run.selected(), selectedResults));
        for (Role role : roles) {
            for (EvaluationQuery query : run.study().queriesIn(part)) {
                EvaluationMetrics.QueryMetric metric = EvaluationMetrics.queryMetric(
                        query,
                        role.results().get(query.queryId()),
                        labels);
                printer.printRecord(
                        part.csvValue(),
                        role.name(),
                        role.config().mode().configValue(),
                        decimal(role.config().categoryWeight(), 1),
                        decimal(role.config().textWeight(), 1),
                        decimal(role.config().threshold(), 3),
                        query.queryId(),
                        query.kind().csvValue(),
                        metric.returnedCount(),
                        metric.relevantCount(),
                        metric.precisionAt5(),
                        metric.returnedPrecision(),
                        metric.hasResults() ? "1" : "0",
                        metric.hasRelevant() ? "1" : "0",
                        String.join(";", metric.returnedCfpbIds()));
            }
        }
    }

    private static String decimal(double value, int scale) {
        return String.format(Locale.ROOT, "%." + scale + "f", value);
    }

    private static void writeLabelAudit(Path path, EvaluationRunResult run)
            throws IOException {
        ObjectNode root = JSON.createObjectNode();
        root.put("studyId", run.study().manifest().studyId());
        root.put("humanValidated", false);
        root.set("reviewers", readOrNull(run.study().manifest().reviewers()));
        root.set("calibration", labelNode(
                "calibration",
                run.study().manifest().calibrationLabels(),
                run.calibrationAgreement(),
                run.packetChecksums().get("calibration"),
                run.checksums()));
        if (run.testUsed()) {
            root.set("final", labelNode(
                    "final",
                    run.study().manifest().finalLabels(),
                    run.testAgreement(),
                    run.packetChecksums().get("final"),
                    run.checksums()));
        }
        Files.writeString(
                path,
                JSON.writerWithDefaultPrettyPrinter()
                                .writeValueAsString(root)
                                .replace("\r\n", "\n")
                        + "\n",
                StandardCharsets.UTF_8);
    }

    private static ObjectNode labelNode(
            String prefix,
            EvaluationManifest.LabelPaths paths,
            EvaluationLabelsFile.AgreementSummary agreement,
            String packetChecksum,
            Map<String, String> checksums) {
        ObjectNode node = JSON.createObjectNode();
        node.put("finalLabels", paths.finalLabels().getFileName().toString());
        node.put("reviewerA", paths.reviewerA().getFileName().toString());
        node.put("reviewerB", paths.reviewerB().getFileName().toString());
        node.put("adjudication", paths.adjudication().getFileName().toString());
        node.set("agreement", agreementNode(agreement));
        node.put("packetInputSha256", packetChecksum);
        ObjectNode checksumNode = node.putObject("fileChecksums");
        checksumNode.put("final", checksums.get(prefix + "Labels"));
        checksumNode.put("reviewerA", checksums.get(prefix + "ReviewA"));
        checksumNode.put("reviewerB", checksums.get(prefix + "ReviewB"));
        checksumNode.put("adjudication", checksums.get(prefix + "Adjudication"));
        return node;
    }

    private static ObjectNode agreementNode(
            EvaluationLabelsFile.AgreementSummary agreement) {
        ObjectNode node = JSON.createObjectNode();
        if (agreement == null) {
            node.put("pairs", 0);
            return node;
        }
        node.put("pairs", agreement.pairs());
        node.put("agreements", agreement.agreements());
        node.put("disagreements", agreement.disagreements());
        node.put("agreementRate", agreement.agreementRate());
        node.put("agreementPercent", agreement.agreementRate() * 100.0);
        if (agreement.cohensKappa() == null) {
            node.putNull("cohensKappa");
        } else {
            node.put("cohensKappa", agreement.cohensKappa());
        }
        return node;
    }

    private static JsonNode readOrNull(Path path) throws IOException {
        if (path == null || !Files.exists(path)) {
            return null;
        }
        return JSON.readTree(Files.readString(path, StandardCharsets.UTF_8));
    }

    private static void writeSummary(Path path, EvaluationRunResult run)
            throws IOException {
        EvaluationManifest manifest = run.study().manifest();
        EvaluationMetrics.Aggregate calibrationBaseline = run.calibrationBaseline();
        EvaluationMetrics.Aggregate calibrationSelected = run.calibrationSelected();
        EvaluationMetrics.Aggregate testBaseline = run.testBaseline();
        EvaluationMetrics.Aggregate testSelected = run.testSelected();

        ObjectNode root = JSON.createObjectNode();
        root.put("studyId", manifest.studyId());
        root.put("studyVersion", 2);
        root.put("evaluation", "relevance-v2");
        root.put("humanValidated", false);
        root.put("labelSource", "independent subagents; not human validated");
        root.put("createdAt", manifest.createdAt());
        root.put("seed", manifest.splitSpec().seed());
        root.put("precisionAt5Denominator", EvaluationMetrics.PRECISION_DENOMINATOR);
        root.put("dictionaryVersion", id.ac.finresolve.domain.similarity.PhraseDictionary.VERSION);
        root.put("dictionarySha256", run.checksums().get("dictionary"));

        ObjectNode sample = root.putObject("sample");
        sample.put("sha256", run.study().corpus().sampleSha256());
        sample.put("records", run.study().corpus().sampleRecordCount());

        ObjectNode dataset = root.putObject("dataset");
        dataset.put("queries", run.study().queries().size());
        dataset.put("complaints", count(run, EvaluationQuery.Kind.COMPLAINT));
        dataset.put("negatives", count(run, EvaluationQuery.Kind.NEGATIVE));
        dataset.put("studyGroups", manifest.expected().studyGroups());
        dataset.put("calibrationQueries", manifest.expected().calibrationTotal());
        dataset.put("calibrationComplaints", manifest.expected().calibrationComplaints());
        dataset.put("calibrationNegatives", manifest.expected().calibrationNegatives());
        dataset.put("testQueries", manifest.expected().testTotal());
        dataset.put("testComplaints", manifest.expected().testComplaints());
        dataset.put("testNegatives", manifest.expected().testNegatives());

        ObjectNode grid = root.putObject("grid");
        grid.put("modes", manifest.grid().modes().size());
        grid.put("configurations", run.study().configs().size());
        grid.put("categoryWeightMin", manifest.grid().categoryWeightMin());
        grid.put("categoryWeightMax", manifest.grid().categoryWeightMax());
        grid.put("categoryWeightStep", manifest.grid().categoryWeightStep());
        grid.put("thresholdMin", manifest.grid().thresholdMin());
        grid.put("thresholdMax", manifest.grid().thresholdMax());
        grid.put("thresholdStep", manifest.grid().thresholdStep());
        grid.put("fieldWeights", "0.35/0.15/0.35/0.15");
        grid.put("limit", manifest.grid().limit());

        root.set("baselineConfiguration", configNode(run.baseline()));
        if (run.bestBaselineMode() != null) {
            root.set("bestBaselineModeConfiguration", configNode(run.bestBaselineMode()));
        }
        if (run.bestPhrasesMode() != null) {
            root.set("bestPhrasesModeConfiguration", configNode(run.bestPhrasesMode()));
        }
        EvaluationConfig bestEligibleBaseline =
                bestEligibleInMode(run, SimilarityMode.BASELINE);
        EvaluationConfig bestEligiblePhrases =
                bestEligibleInMode(run, SimilarityMode.PHRASES);
        if (bestEligibleBaseline != null) {
            root.set("bestEligibleBaselineModeConfiguration",
                    configNode(bestEligibleBaseline));
        }
        if (bestEligiblePhrases != null) {
            root.set("bestEligiblePhrasesModeConfiguration",
                    configNode(bestEligiblePhrases));
        }
        root.set("selectedConfiguration", configNode(run.selected()));
        root.put("decision", run.decision());
        root.set("appliedConfiguration", configNode(run.appliedConfiguration()));
        root.set("rollbackConfiguration", configNode(manifest.rollback()));
        root.put("testUsed", run.testUsed());

        ObjectNode calibration = root.putObject("calibration");
        calibration.set("baseline", aggregateNode(calibrationBaseline));
        calibration.set("selected", aggregateNode(calibrationSelected));
        if (run.bestBaselineMode() != null) {
            calibration.set(
                    "bestBaselineMode",
                    aggregateNode(run.calibrationAggregates().get(run.bestBaselineMode())));
            calibration.put(
                    "bestBaselineModeEligible",
                    EvaluationSelection.isEligible(
                            run.calibrationAggregates().get(run.bestBaselineMode()),
                            calibrationBaseline));
        }
        if (run.bestPhrasesMode() != null) {
            calibration.set(
                    "bestPhrasesMode",
                    aggregateNode(run.calibrationAggregates().get(run.bestPhrasesMode())));
            calibration.put(
                    "bestPhrasesModeEligible",
                    EvaluationSelection.isEligible(
                            run.calibrationAggregates().get(run.bestPhrasesMode()),
                            calibrationBaseline));
        }
        if (bestEligibleBaseline != null) {
            calibration.set(
                    "bestEligibleBaselineMode",
                    aggregateNode(run.calibrationAggregates().get(bestEligibleBaseline)));
        }
        if (bestEligiblePhrases != null) {
            calibration.set(
                    "bestEligiblePhrasesMode",
                    aggregateNode(run.calibrationAggregates().get(bestEligiblePhrases)));
        }
        calibration.put("precisionImproved", run.selection().precisionImproved());
        calibration.put("eligibleCandidates", run.selection().eligibleCandidates());

        if (run.testUsed()) {
            ObjectNode test = root.putObject("test");
            test.set("baseline", aggregateNode(testBaseline));
            test.set("selected", aggregateNode(testSelected));
            EvaluationSelection.Gate gate = run.gate();
            ObjectNode gateNode = test.putObject("gate");
            gateNode.put("precisionAt5Increased", gate.precisionAt5Increased());
            gateNode.put("macroPrecisionNotLower", gate.macroPrecisionNotLower());
            gateNode.put("microPrecisionNotLower", gate.microPrecisionNotLower());
            gateNode.put("complaintSuccessNotLower", gate.complaintSuccessNotLower());
            gateNode.put("negativeFailuresNotIncreased", gate.negativeFailuresNotIncreased());
            gateNode.put("passed", gate.passed());
        }

        ObjectNode agreement = root.putObject("agreement");
        agreement.set("calibration", agreementNode(run.calibrationAgreement()));
        if (run.testUsed()) {
            agreement.set("final", agreementNode(run.testAgreement()));
        }

        ObjectNode checksums = root.putObject("checksums");
        run.checksums().forEach(checksums::put);
        ObjectNode packets = checksums.putObject("packets");
        run.packetChecksums().forEach(packets::put);

        ObjectNode labels = root.putObject("labels");
        labels.put("calibration", relative(
                manifest.repositoryRoot(), manifest.calibrationLabels().finalLabels()));
        if (run.testUsed()) {
            labels.put("final", relative(
                    manifest.repositoryRoot(), manifest.finalLabels().finalLabels()));
        }
        labels.put("humanJudgmentsNotPopulated", true);

        ArrayNode limitations = root.putArray("limitations");
        limitations.add("Relevance labels were produced by independent subagents, not humans.");
        limitations.add("The labels are AI judgments with humanValidated=false.");
        limitations.add("The 500-case sample does not represent all CFPB complaints.");
        limitations.add("Recall is not reported because the full corpus is not exhaustively labeled.");
        limitations.add("Similarity and historical responses do not prove successful handling.");
        limitations.add("Candidate comparisons are limited to the pooled top-5 union.");

        String json = JSON.writerWithDefaultPrettyPrinter()
                .writeValueAsString(root)
                .replace("\r\n", "\n")
                + "\n";
        Files.writeString(path, json, StandardCharsets.UTF_8);
    }

    private static int count(EvaluationRunResult run, EvaluationQuery.Kind kind) {
        return (int) run.study().queries().stream()
                .filter(query -> query.kind() == kind)
                .count();
    }

    private static ObjectNode configNode(EvaluationConfig config) {
        ObjectNode node = JSON.createObjectNode();
        node.put("mode", config.mode().configValue());
        node.put("algorithmVersion", config.mode().algorithmVersion());
        node.put("categoryWeight", config.categoryWeight());
        node.put("textWeight", config.textWeight());
        node.put("threshold", config.threshold());
        node.put("label", config.label());
        return node;
    }

    private static ObjectNode aggregateNode(EvaluationMetrics.Aggregate aggregate) {
        return EvaluationSelectionFile.aggregateNode(aggregate);
    }

    private static void writeMarkdown(Path path, EvaluationRunResult run) throws IOException {
        EvaluationManifest manifest = run.study().manifest();
        StringBuilder markdown = new StringBuilder();
        markdown.append("# Evaluasi relevansi pencarian v2 — 27 Oktober 2026\n\n");
        markdown.append("> Status: **penilaian AI independen, belum divalidasi manusia** ")
                .append("(`humanValidated=false`). Label tidak ditampilkan pada API dan tidak ")
                .append("mengubah hasil pencarian kecuali konfigurasi yang lolos gerbang ")
                .append("penerapan.\n\n");

        markdown.append("| Properti | Nilai |\n| --- | --- |\n");
        markdown.append("| Studi | `").append(manifest.studyId()).append("` |\n");
        markdown.append("| Sampel | ").append(run.study().corpus().sampleRecordCount())
                .append(" kasus, SHA-256 `")
                .append(run.study().corpus().sampleSha256()).append("` |\n");
        markdown.append("| Query | ").append(run.study().queries().size())
                .append(" (")
                .append(manifest.expected().complaints()).append(" pengaduan, ")
                .append(manifest.expected().negatives()).append(" negatif) |\n");
        markdown.append("| Kelompok skenario baru | ")
                .append(manifest.expected().studyGroups()).append(" kelompok × 2 parafrasa |\n");
        markdown.append("| Kalibrasi | ")
                .append(manifest.expected().calibrationTotal()).append(" query |\n");
        markdown.append("| Pengujian | ")
                .append(manifest.expected().testTotal()).append(" query |\n");
        markdown.append("| Seed pembagian | `")
                .append(manifest.splitSpec().seed()).append("` |\n");
        markdown.append("| Kamus frasa | `")
                .append(id.ac.finresolve.domain.similarity.PhraseDictionary.VERSION)
                .append("` SHA-256 `").append(run.checksums().get("dictionary"))
                .append("` |\n");
        markdown.append("| Baseline | `").append(run.baseline().label()).append("` |\n");
        markdown.append("| Terpilih | `").append(run.selected().label()).append("` |\n");
        markdown.append("| Keputusan | **").append(decisionLabel(run.decision()))
                .append("** |\n\n");

        if (run.selection().precisionImproved()) {
            markdown.append("Kandidat lolos seleksi kalibrasi. Pengujian akhir")
                    .append(run.testUsed() ? " dijalankan sekali" : " **tidak dijalankan**")
                    .append(" dan tidak dipakai untuk memilih ulang.\n\n");
        } else {
            markdown.append("Tidak ada peningkatan precision@5 pada kalibrasi; baseline ")
                    .append("dipertahankan dan bagian pengujian **tidak digunakan**.\n\n");
        }

        markdown.append("## Audit penilaian subagent\n\n");
        appendAgreement(markdown, "Kalibrasi", run.calibrationAgreement());
        if (run.testUsed()) {
            appendAgreement(markdown, "Pengujian akhir", run.testAgreement());
        }
        markdown.append("\nReviewer: `reviewer-a` dan `reviewer-b` (")
                .append("deepseek-v4.1-flash, konteks terpisah), keputusan beda ")
                .append("diselesaikan `adjudicator` (deepseek-v4.1-flash). Paket review ")
                .append("hanya memuat identitas pasangan, narasi, dan kategori; skor, ")
                .append("peringkat, konfigurasi, label lama, pembagian, dan penanda negatif ")
                .append("disembunyikan. Checksum input: `")
                .append(run.packetChecksums().getOrDefault("calibration", "—"))
                .append("`")
                .append(run.testUsed()
                        ? " (kalibrasi), `"
                                + run.packetChecksums().getOrDefault("final", "—")
                                + "` (pengujian).\n\n"
                        : ".\n\n");
        markdown.append("Label yang hilang, duplikat, atau adjudikasi yang belum selesai ")
                .append("menggagalkan evaluasi. `judgments.csv` manusia tetap kosong.\n\n");

        markdown.append("## Kalibrasi (hanya bagian ini yang dipakai memilih)\n\n");
        markdown.append("Grid: 2 mode × 7 bobot kategori × 21 ambang = **")
                .append(run.study().configs().size()).append(" konfigurasi**; bobot teks ")
                .append("adalah komplemen, bobot field 0.35/0.15/0.35/0.15, limit 5.\n\n");
        markdown.append(calibrationTable(run));
        markdown.append("\nKelayakan kandidat: macro precision, micro precision, dan ")
                .append("keberhasilan per query tidak di bawah baseline; kegagalan negatif ")
                .append("tidak melebihi baseline. Pemilihan memakai precision@5 tertinggi, ")
                .append("lalu micro precision, keberhasilan per query, lebih sedikit ")
                .append("kegagalan negatif, mode baseline, lalu jarak parameter.\n\n");

        if (run.testUsed()) {
            markdown.append("## Pengujian akhir (sekali)\n\n");
            markdown.append(comparisonTable(run.testBaseline(), run.testSelected()));
            EvaluationSelection.Gate gate = run.gate();
            markdown.append("\n| Syarat penerapan | Hasil |\n| --- | --- |\n");
            markdown.append("| precision@5 meningkat | ")
                    .append(gate.precisionAt5Increased() ? "ya" : "**tidak**").append(" |\n");
            markdown.append("| macro precision tidak menurun | ")
                    .append(gate.macroPrecisionNotLower() ? "ya" : "**tidak**").append(" |\n");
            markdown.append("| micro precision tidak menurun | ")
                    .append(gate.microPrecisionNotLower() ? "ya" : "**tidak**").append(" |\n");
            markdown.append("| keberhasilan per query tidak menurun | ")
                    .append(gate.complaintSuccessNotLower() ? "ya" : "**tidak**").append(" |\n");
            markdown.append("| kegagalan negatif tidak bertambah | ")
                    .append(gate.negativeFailuresNotIncreased() ? "ya" : "**tidak**")
                    .append(" |\n");
            markdown.append("| **Gerbang** | **")
                    .append(gate.passed() ? "LULUS" : "GAGAL").append("** |\n\n");
        }

        markdown.append("## Contoh hasil\n\n");
        appendExamples(markdown, run);

        markdown.append("## Keputusan dan rollback\n\n");
        if ("APPLY_SELECTED".equals(run.decision())) {
            markdown.append("Konfigurasi terpilih diterapkan sebagai default: **")
                    .append(run.appliedConfiguration().label()).append("**.\n");
        } else if ("KEEP_BASELINE_TEST".equals(run.decision())) {
            markdown.append("Kandidat gagal gerbang pengujian; default baseline **")
                    .append(run.baseline().label())
                    .append("** dipertahankan dan tidak ada kandidat pengganti yang dipilih ")
                    .append("dari hasil pengujian.\n");
        } else {
            markdown.append("Tidak ada peningkatan precision@5 pada kalibrasi; default ")
                    .append("baseline **").append(run.baseline().label())
                    .append("** dipertahankan dan bagian pengujian tidak dipakai.\n");
        }
        markdown.append("\nRollback: `finresolve.similarity.mode=baseline`, ")
                .append("`category-weight=0.4`, `text-weight=0.6`, `threshold=0.50`. ")
                .append("Tidak ada migrasi database.\n\n");

        markdown.append("## Batas\n\n");
        markdown.append("- Label dibuat oleh subagent AI independen, bukan manusia; ")
                .append("`humanValidated=false`.\n");
        markdown.append("- Sampel 500 kasus hanya mencakup dua produk dan tidak mewakili ")
                .append("seluruh CFPB.\n");
        markdown.append("- Recall seluruh korpus tidak dihitung; kandidat dinilai pada ")
                .append("gabungan top-5 semua konfigurasi.\n");
        markdown.append("- Skor kemiripan bukan probabilitas relevansi atau keberhasilan ")
                .append("penanganan.\n");
        markdown.append("- Perbandingan pengujian terbatas pada baseline saat ini dan ")
                .append("kandidat terpilih yang dibekukan.\n");
        Files.writeString(path, markdown.toString(), StandardCharsets.UTF_8);
    }

    private static String decisionLabel(String decision) {
        return switch (decision) {
            case "APPLY_SELECTED" -> "terapkan konfigurasi terpilih";
            case "KEEP_BASELINE_TEST" -> "pertahankan baseline setelah pengujian";
            default -> "pertahankan baseline; pengujian tidak dipakai";
        };
    }

    private static void appendAgreement(
            StringBuilder markdown,
            String label,
            EvaluationLabelsFile.AgreementSummary agreement) {
        if (agreement == null) {
            markdown.append("- ").append(label).append(": tidak ada data.\n");
            return;
        }
        markdown.append("- ").append(label).append(": ")
                .append(agreement.pairs()).append(" pasangan, ")
                .append(agreement.agreements()).append(" sepakat, ")
                .append(agreement.disagreements()).append(" beda; kesepakatan ")
                .append(formatPercent(agreement.agreementRate()))
                .append(", Cohen's kappa ")
                .append(agreement.cohensKappa() == null
                        ? "tidak terdefinisi"
                        : String.format(Locale.ROOT, "%.4f", agreement.cohensKappa()))
                .append(".\n");
    }

    private static String calibrationTable(EvaluationRunResult run) {
        EvaluationMetrics.Aggregate baseline = run.calibrationBaseline();
        EvaluationConfig bestBaselineConfig =
                bestEligibleInMode(run, SimilarityMode.BASELINE);
        EvaluationConfig bestPhrasesConfig =
                bestEligibleInMode(run, SimilarityMode.PHRASES);
        EvaluationMetrics.Aggregate bestBaseline = bestBaselineConfig == null
                ? null
                : run.calibrationAggregates().get(bestBaselineConfig);
        EvaluationMetrics.Aggregate bestPhrases = bestPhrasesConfig == null
                ? null
                : run.calibrationAggregates().get(bestPhrasesConfig);

        StringBuilder table = new StringBuilder();
        table.append("| Metrik | Baseline saat ini | Baseline terbaik layak | ")
                .append("Frasa terbaik layak | Terpilih |\n");
        table.append("| --- | ---: | ---: | ---: | ---: |\n");
        table.append(metricRow(
                "precision@5",
                baseline, bestBaseline, bestPhrases, run.calibrationSelected()));
        table.append(metricRow(
                "Macro precision",
                baseline, bestBaseline, bestPhrases, run.calibrationSelected()));
        table.append(metricRow(
                "Micro precision",
                baseline, bestBaseline, bestPhrases, run.calibrationSelected()));
        table.append(metricRow(
                "Keberhasilan per query",
                baseline, bestBaseline, bestPhrases, run.calibrationSelected()));
        table.append(metricRow(
                "Hasil pengaduan",
                baseline, bestBaseline, bestPhrases, run.calibrationSelected()));
        table.append(metricRow(
                "Hasil negatif",
                baseline, bestBaseline, bestPhrases, run.calibrationSelected()));
        table.append(metricRow(
                "Total hasil",
                baseline, bestBaseline, bestPhrases, run.calibrationSelected()));
        table.append(metricRow(
                "Kegagalan negatif",
                baseline, bestBaseline, bestPhrases, run.calibrationSelected()));
        table.append(metricRow(
                "Proporsi query negatif",
                baseline, bestBaseline, bestPhrases, run.calibrationSelected()));
        table.append("\n| Konfigurasi | ")
                .append(run.baseline().label()).append(" | ")
                .append(bestBaselineConfig == null ? "—" : bestBaselineConfig.label())
                .append(" | ")
                .append(bestPhrasesConfig == null ? "—" : bestPhrasesConfig.label())
                .append(" | ").append(run.selected().label()).append(" |\n");
        table.append("| Layak | ya | ")
                .append(bestBaselineConfig == null ? "—" : "ya").append(" | ")
                .append(bestPhrasesConfig == null ? "—" : "ya").append(" | ")
                .append(eligible(run, run.selected(), baseline)).append(" |\n");
        table.append("\n");
        appendRawBest(table, run, "Baseline mentah terbaik", run.bestBaselineMode());
        appendRawBest(table, run, "Frasa mentah terbaik", run.bestPhrasesMode());
        return table.toString();
    }

    private static void appendRawBest(
            StringBuilder table, EvaluationRunResult run, String label,
            EvaluationConfig config) {
        if (config == null) {
            return;
        }
        EvaluationMetrics.Aggregate aggregate =
                run.calibrationAggregates().get(config);
        table.append("- ").append(label).append(": `").append(config.label())
                .append("` — precision@5 ")
                .append(String.format(Locale.ROOT, "%.3f", aggregate.meanPrecisionAt5()))
                .append(", kegagalan negatif ").append(aggregate.negativeFailures())
                .append(" (").append(EvaluationSelection.isEligible(
                        aggregate, run.calibrationBaseline()) ? "layak" : "tidak layak")
                .append(").\n");
    }

    private static EvaluationConfig bestEligibleInMode(
            EvaluationRunResult run, SimilarityMode mode) {
        EvaluationMetrics.Aggregate baseline = run.calibrationBaseline();
        return run.study().configs().stream()
                .filter(config -> config.mode() == mode)
                .filter(config -> EvaluationSelection.isEligible(
                        run.calibrationAggregates().get(config), baseline))
                .min(Comparator.comparingDouble(
                                (EvaluationConfig config) -> run.calibrationAggregates()
                                        .get(config).meanPrecisionAt5())
                        .reversed()
                        .thenComparing(Comparator.comparingDouble(
                                        (EvaluationConfig config) -> run
                                                .calibrationAggregates().get(config)
                                                .microPrecision())
                                .reversed())
                        .thenComparing(Comparator.comparingDouble(
                                        (EvaluationConfig config) -> run
                                                .calibrationAggregates().get(config)
                                                .complaintSuccessRate())
                                .reversed())
                        .thenComparingInt(config -> run.calibrationAggregates()
                                .get(config).negativeFailures())
                        .thenComparingInt(config -> config.mode() == SimilarityMode.BASELINE
                                ? 0
                                : 1)
                        .thenComparingInt(config -> config
                                .distanceToBaselineInGridSteps(
                                        run.baseline(), run.study().grid()))
                        .thenComparingDouble(EvaluationConfig::categoryWeight)
                        .thenComparingDouble(EvaluationConfig::threshold))
                .orElse(null);
    }

    private static String eligible(
            EvaluationRunResult run,
            EvaluationConfig config,
            EvaluationMetrics.Aggregate baseline) {
        if (config == null) {
            return "—";
        }
        return EvaluationSelection.isEligible(
                run.calibrationAggregates().get(config), baseline) ? "ya" : "tidak";
    }

    private static String metricRow(
            String label,
            EvaluationMetrics.Aggregate baseline,
            EvaluationMetrics.Aggregate bestBaseline,
            EvaluationMetrics.Aggregate bestPhrases,
            EvaluationMetrics.Aggregate selected) {
        return switch (label) {
            case "precision@5" -> String.format(
                    Locale.ROOT, "| %s | %.3f | %.3f | %.3f | %.3f |%n",
                    label, baseline.meanPrecisionAt5(),
                    value(bestBaseline, a -> a.meanPrecisionAt5()),
                    value(bestPhrases, a -> a.meanPrecisionAt5()),
                    selected.meanPrecisionAt5());
            case "Macro precision" -> String.format(
                    Locale.ROOT, "| %s | %.3f | %.3f | %.3f | %.3f |%n",
                    label, baseline.macroPrecision(),
                    value(bestBaseline, a -> a.macroPrecision()),
                    value(bestPhrases, a -> a.macroPrecision()),
                    selected.macroPrecision());
            case "Micro precision" -> String.format(
                    Locale.ROOT, "| %s | %.3f | %.3f | %.3f | %.3f |%n",
                    label, baseline.microPrecision(),
                    value(bestBaseline, a -> a.microPrecision()),
                    value(bestPhrases, a -> a.microPrecision()),
                    selected.microPrecision());
            case "Keberhasilan per query" -> String.format(
                    Locale.ROOT, "| %s | %.3f | %.3f | %.3f | %.3f |%n",
                    label, baseline.complaintSuccessRate(),
                    value(bestBaseline, a -> a.complaintSuccessRate()),
                    value(bestPhrases, a -> a.complaintSuccessRate()),
                    selected.complaintSuccessRate());
            case "Hasil pengaduan" -> String.format(
                    Locale.ROOT, "| %s | %d | %d | %d | %d |%n",
                    label, baseline.complaintReturned(),
                    valueInt(bestBaseline, a -> a.complaintReturned()),
                    valueInt(bestPhrases, a -> a.complaintReturned()),
                    selected.complaintReturned());
            case "Hasil negatif" -> String.format(
                    Locale.ROOT, "| %s | %d | %d | %d | %d |%n",
                    label, baseline.negativeReturned(),
                    valueInt(bestBaseline, a -> a.negativeReturned()),
                    valueInt(bestPhrases, a -> a.negativeReturned()),
                    selected.negativeReturned());
            case "Total hasil" -> String.format(
                    Locale.ROOT, "| %s | %d | %d | %d | %d |%n",
                    label, baseline.totalReturned(),
                    valueInt(bestBaseline, a -> a.totalReturned()),
                    valueInt(bestPhrases, a -> a.totalReturned()),
                    selected.totalReturned());
            case "Proporsi query negatif" -> String.format(
                    Locale.ROOT, "| %s | %.3f | %.3f | %.3f | %.3f |%n",
                    label, baseline.negativeFailureRate(),
                    value(bestBaseline, a -> a.negativeFailureRate()),
                    value(bestPhrases, a -> a.negativeFailureRate()),
                    selected.negativeFailureRate());
            default -> String.format(
                    Locale.ROOT, "| %s | %d | %d | %d | %d |%n",
                    label, baseline.negativeFailures(),
                    valueInt(bestBaseline, a -> a.negativeFailures()),
                    valueInt(bestPhrases, a -> a.negativeFailures()),
                    selected.negativeFailures());
        };
    }

    private static double value(
            EvaluationMetrics.Aggregate aggregate,
            java.util.function.ToDoubleFunction<EvaluationMetrics.Aggregate> mapper) {
        return aggregate == null ? 0.0 : mapper.applyAsDouble(aggregate);
    }

    private static int valueInt(
            EvaluationMetrics.Aggregate aggregate,
            java.util.function.ToIntFunction<EvaluationMetrics.Aggregate> mapper) {
        return aggregate == null ? 0 : mapper.applyAsInt(aggregate);
    }

    private static String comparisonTable(
            EvaluationMetrics.Aggregate baseline,
            EvaluationMetrics.Aggregate selected) {
        return "| Metrik | Baseline | Terpilih |\n"
                + "| --- | ---: | ---: |\n"
                + row("precision@5", baseline.meanPrecisionAt5(), selected.meanPrecisionAt5())
                + row("Macro precision", baseline.macroPrecision(), selected.macroPrecision())
                + row("Micro precision", baseline.microPrecision(), selected.microPrecision())
                + row("Keberhasilan per query", baseline.complaintSuccessRate(),
                        selected.complaintSuccessRate())
                + rowInt("Hasil pengaduan", baseline.complaintReturned(),
                        selected.complaintReturned())
                + rowInt("Hasil negatif", baseline.negativeReturned(),
                        selected.negativeReturned())
                + rowInt("Total hasil", baseline.totalReturned(), selected.totalReturned())
                + rowInt("Query negatif mendapat hasil", baseline.negativeFailures(),
                        selected.negativeFailures())
                + row("Proporsi query negatif mendapat hasil", baseline.negativeFailureRate(),
                        selected.negativeFailureRate());
    }

    private static String row(String label, double baseline, double selected) {
        return String.format(
                Locale.ROOT, "| %s | %.3f | %.3f |%n", label, baseline, selected);
    }

    private static String rowInt(String label, int baseline, int selected) {
        return String.format(Locale.ROOT, "| %s | %d | %d |%n", label, baseline, selected);
    }

    private static void appendExamples(StringBuilder markdown, EvaluationRunResult run) {
        EvaluationSplit.Part part = run.testUsed()
                ? EvaluationSplit.Part.TEST
                : EvaluationSplit.Part.CALIBRATION;
        Map<String, List<SimilarityResult>> results = run.testUsed()
                ? run.testSelectedResults()
                : run.calibrationSelectedResults();
        EvaluationLabels labels = run.testUsed() ? run.finalLabels() : run.calibrationLabels();
        Map<String, CaseRecord> cases = new HashMap<>();
        run.study().corpus().cases().forEach(
                record -> cases.put(EvaluationCaseIds.complaintId(record), record));

        EvaluationQuery success = null;
        EvaluationQuery failure = null;
        int best = -1;
        int worst = Integer.MAX_VALUE;
        for (EvaluationQuery query : run.study().queriesIn(part)) {
            if (query.kind() != EvaluationQuery.Kind.COMPLAINT) {
                continue;
            }
            int relevant = relevantCount(query, results.get(query.queryId()), labels);
            if (relevant > best) {
                best = relevant;
                success = query;
            }
            if (relevant < worst) {
                worst = relevant;
                failure = query;
            }
        }
        markdown.append("### Keberhasilan\n\n");
        appendQueryExample(markdown, run, part, labels, cases, success, results);
        markdown.append("\n### Kegagalan\n\n");
        appendQueryExample(markdown, run, part, labels, cases, failure, results);

        List<String> negativeLines = new ArrayList<>();
        for (EvaluationQuery query : run.study().queriesIn(part)) {
            if (query.kind() != EvaluationQuery.Kind.NEGATIVE) {
                continue;
            }
            List<SimilarityResult> queryResults = results.get(query.queryId());
            if (queryResults == null || queryResults.isEmpty()) {
                continue;
            }
            negativeLines.add("\n**Negatif " + query.queryId() + "** — "
                    + query.scenario() + "\n");
            for (SimilarityResult result : queryResults) {
                String cfpbId = EvaluationCaseIds.complaintId(result.caseRecord());
                negativeLines.add("- `" + cfpbId + "` — "
                        + labels.reason(query.queryId(), cfpbId) + "\n");
            }
        }
        if (!negativeLines.isEmpty()) {
            markdown.append("\n### Query negatif yang tetap mendapat hasil\n\n");
            negativeLines.forEach(markdown::append);
        }
    }

    private static void appendQueryExample(
            StringBuilder markdown,
            EvaluationRunResult run,
            EvaluationSplit.Part part,
            EvaluationLabels labels,
            Map<String, CaseRecord> cases,
            EvaluationQuery query,
            Map<String, List<SimilarityResult>> results) {
        if (query == null) {
            markdown.append("Tidak ada query pada bagian ini.\n");
            return;
        }
        markdown.append("**").append(query.queryId()).append("** (").append(part.csvValue())
                .append(") — ").append(query.scenario()).append("\n\n");
        markdown.append("> ").append(query.narrative()).append("\n\n");
        List<SimilarityResult> queryResults = results.get(query.queryId());
        if (queryResults == null || queryResults.isEmpty()) {
            markdown.append("Tidak ada hasil.\n");
            return;
        }
        for (SimilarityResult result : queryResults) {
            String cfpbId = EvaluationCaseIds.complaintId(result.caseRecord());
            markdown.append("- `").append(cfpbId).append("` — **")
                    .append(labels.relevant(query.queryId(), cfpbId)
                            ? "relevan"
                            : "tidak relevan")
                    .append("**: ").append(labels.reason(query.queryId(), cfpbId))
                    .append("  \n  _").append(excerpt(
                            cases.get(cfpbId).narrative(), 260))
                    .append("_\n");
        }
    }

    private static int relevantCount(
            EvaluationQuery query,
            List<SimilarityResult> results,
            EvaluationLabels labels) {
        if (results == null) {
            return 0;
        }
        int count = 0;
        for (SimilarityResult result : results) {
            if (labels.relevant(
                    query.queryId(), EvaluationCaseIds.complaintId(result.caseRecord()))) {
                count++;
            }
        }
        return count;
    }

    private static String excerpt(String text, int limit) {
        String normalized = text == null ? "" : text.replaceAll("\\s+", " ").strip();
        return normalized.length() <= limit
                ? normalized
                : normalized.substring(0, limit - 1) + "…";
    }

    private static String formatPercent(double value) {
        return String.format(Locale.ROOT, "%.2f%%", value * 100.0);
    }

    private static String relative(Path root, Path path) {
        try {
            return root.toAbsolutePath().normalize()
                    .relativize(path.toAbsolutePath().normalize())
                    .toString();
        } catch (IllegalArgumentException failure) {
            return path.toString();
        }
    }

    private record Role(
            String name,
            EvaluationConfig config,
            Map<String, List<SimilarityResult>> results) {
    }

    /** Prints the stored summary for the compare command. */
    public static String compareSummary(Path summaryJson) throws IOException {
        JsonNode root = JSON.readTree(Files.readString(summaryJson, StandardCharsets.UTF_8));
        StringBuilder output = new StringBuilder();
        output.append("Studi: ").append(root.get("studyId").asString()).append('\n');
        output.append("Keputusan: ").append(root.get("decision").asString()).append('\n');
        output.append("Baseline: ")
                .append(root.get("baselineConfiguration").get("label").asString())
                .append('\n');
        output.append("Terpilih: ")
                .append(root.get("selectedConfiguration").get("label").asString())
                .append('\n');
        output.append("Diterapkan: ")
                .append(root.get("appliedConfiguration").get("label").asString())
                .append("\n\n");
        appendAggregate(output, "Kalibrasi baseline", root.get("calibration").get("baseline"));
        appendAggregate(output, "Kalibrasi terpilih", root.get("calibration").get("selected"));
        if (root.get("test") != null) {
            appendAggregate(output, "Uji baseline", root.get("test").get("baseline"));
            appendAggregate(output, "Uji terpilih", root.get("test").get("selected"));
        } else {
            output.append("Pengujian tidak digunakan.\n");
        }
        return output.toString();
    }

    private static void appendAggregate(
            StringBuilder output, String name, JsonNode aggregate) {
        output.append(name)
                .append(": P@5=")
                .append(String.format(
                        Locale.ROOT, "%.3f", aggregate.get("meanPrecisionAt5").asDouble()))
                .append(", macro=")
                .append(String.format(
                        Locale.ROOT, "%.3f", aggregate.get("macroPrecision").asDouble()))
                .append(", micro=")
                .append(String.format(
                        Locale.ROOT, "%.3f", aggregate.get("microPrecision").asDouble()))
                .append(", hasil=")
                .append(aggregate.get("complaintReturned").asInt())
                .append(", negatif gagal=")
                .append(aggregate.get("negativeFailures").asInt())
                .append('\n');
    }
}
