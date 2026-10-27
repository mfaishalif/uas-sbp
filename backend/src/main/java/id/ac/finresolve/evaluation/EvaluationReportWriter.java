package id.ac.finresolve.evaluation;

import id.ac.finresolve.similarity.SimilarityResult;
import id.ac.finresolve.similarity.TextRules;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Writes the committed calibration/test CSV, JSON, and Markdown artifacts. */
public final class EvaluationReportWriter {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private EvaluationReportWriter() {
    }

    public static void write(Path outputDirectory, EvaluationStudy study) {
        try {
            Files.createDirectories(outputDirectory);
            EvaluationMetrics.Aggregate calibrationBaseline = aggregate(
                    study, EvaluationSplit.Part.CALIBRATION, study.baseline(),
                    study.calibrationBaselineResults());
            EvaluationMetrics.Aggregate calibrationSelected = aggregate(
                    study, EvaluationSplit.Part.CALIBRATION, study.selected(),
                    study.calibrationSelectedResults());
            EvaluationMetrics.Aggregate testBaseline = aggregate(
                    study, EvaluationSplit.Part.TEST, study.baseline(),
                    study.testBaselineResults());
            EvaluationMetrics.Aggregate testSelected = aggregate(
                    study, EvaluationSplit.Part.TEST, study.selected(),
                    study.testSelectedResults());
            EvaluationSelection.Gate gate = EvaluationSelection.gate(testBaseline, testSelected);

            writeCalibrationCsv(
                    outputDirectory.resolve("calibration-configurations.csv"), study);
            writePerQueryCsv(
                    outputDirectory.resolve("per-query-results.csv"), study);
            writeSummaryJson(
                    outputDirectory.resolve("summary.json"),
                    study,
                    calibrationBaseline,
                    calibrationSelected,
                    testBaseline,
                    testSelected,
                    gate);
            writeMarkdown(
                    outputDirectory.resolve("report.md"),
                    study,
                    calibrationBaseline,
                    calibrationSelected,
                    testBaseline,
                    testSelected,
                    gate);
        } catch (IOException failure) {
            throw new IllegalArgumentException(
                    "Cannot write evaluation artifacts to " + outputDirectory, failure);
        }
    }

    private static EvaluationMetrics.Aggregate aggregate(
            EvaluationStudy study,
            EvaluationSplit.Part part,
            EvaluationConfig config,
            Map<String, List<SimilarityResult>> results) {
        EvaluationMetrics.Aggregate stored = study.calibrationAggregates().get(config);
        if (part == EvaluationSplit.Part.CALIBRATION && stored != null) {
            return stored;
        }
        return EvaluationMetrics.aggregate(
                study.split().queriesIn(study.queries(), part), results, study.labels());
    }

    private static void writeCalibrationCsv(Path path, EvaluationStudy study)
            throws IOException {
        try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8);
                CSVPrinter printer = new CSVPrinter(
                        writer,
                        CSVFormat.DEFAULT.builder()
                                .setHeader(
                                        "categoryWeight",
                                        "textWeight",
                                        "threshold",
                                        "meanPrecisionAt5",
                                        "meanReturnedPrecision",
                                        "complaintReturned",
                                        "negativeReturned",
                                        "totalReturned",
                                        "negativeFailures",
                                        "negativeFailureRate",
                                        "distanceToBaselineInGridSteps",
                                        "selected")
                                .setRecordSeparator("\n")
                                .build())) {
            for (EvaluationConfig config : EvaluationConfig.grid()) {
                EvaluationMetrics.Aggregate aggregate =
                        study.calibrationAggregates().get(config);
                if (aggregate == null) {
                    throw new IllegalArgumentException(
                            "Missing calibration metrics for " + config.label());
                }
                printer.printRecord(
                        config.categoryWeight(),
                        config.textWeight(),
                        config.threshold(),
                        aggregate.meanPrecisionAt5(),
                        aggregate.meanReturnedPrecision(),
                        aggregate.complaintReturned(),
                        aggregate.negativeReturned(),
                        aggregate.totalReturned(),
                        aggregate.negativeFailures(),
                        aggregate.negativeFailureRate(),
                        config.distanceToBaselineInGridSteps(),
                        config.sameAs(study.selected()) ? "1" : "0");
            }
        }
    }

    private static void writePerQueryCsv(Path path, EvaluationStudy study) throws IOException {
        try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8);
                CSVPrinter printer = new CSVPrinter(
                        writer,
                        CSVFormat.DEFAULT.builder()
                                .setHeader(
                                        "split",
                                        "role",
                                        "categoryWeight",
                                        "threshold",
                                        "queryId",
                                        "kind",
                                        "returnedCount",
                                        "relevantCount",
                                        "precisionAt5",
                                        "returnedPrecision",
                                        "hasResults",
                                        "returnedCfpbIds")
                                .setRecordSeparator("\n")
                                .build())) {
            for (EvaluationSplit.Part part : EvaluationSplit.Part.values()) {
                for (Role role : List.of(
                        new Role("baseline", study.baseline(),
                                part == EvaluationSplit.Part.CALIBRATION
                                        ? study.calibrationBaselineResults()
                                        : study.testBaselineResults()),
                        new Role("selected", study.selected(),
                                part == EvaluationSplit.Part.CALIBRATION
                                        ? study.calibrationSelectedResults()
                                        : study.testSelectedResults()))) {
                    for (EvaluationQuery query : study.split().queriesIn(
                            study.queries(), part)) {
                        EvaluationMetrics.QueryMetric metric =
                                EvaluationMetrics.queryMetric(
                                        query,
                                        role.results().get(query.queryId()),
                                        study.labels());
                        printer.printRecord(
                                part.csvValue(),
                                role.name(),
                                role.config().categoryWeight(),
                                role.config().threshold(),
                                query.queryId(),
                                query.kind().csvValue(),
                                metric.returnedCount(),
                                metric.relevantCount(),
                                metric.precisionAt5(),
                                metric.returnedPrecision(),
                                metric.hasResults() ? "1" : "0",
                                String.join(";", metric.returnedCfpbIds()));
                    }
                }
            }
        }
    }

    private static void writeSummaryJson(
            Path path,
            EvaluationStudy study,
            EvaluationMetrics.Aggregate calibrationBaseline,
            EvaluationMetrics.Aggregate calibrationSelected,
            EvaluationMetrics.Aggregate testBaseline,
            EvaluationMetrics.Aggregate testSelected,
            EvaluationSelection.Gate gate) throws IOException {
        ObjectNode root = JSON.createObjectNode();
        root.put("evaluation", "exploratory");
        root.put("humanValidated", false);
        root.put("algorithmVersion", TextRules.ALGORITHM_VERSION);
        root.put("seed", EvaluationSplit.SEED);
        root.put("precisionAt5Denominator", EvaluationMetrics.PRECISION_DENOMINATOR);

        ObjectNode sample = root.putObject("sample");
        sample.put("sha256", study.sampleSha256());
        sample.put("records", study.sampleRecordCount());

        ObjectNode split = root.putObject("split");
        split.put("seed", EvaluationSplit.SEED);
        for (EvaluationSplit.Part part : EvaluationSplit.Part.values()) {
            List<EvaluationQuery> partQueries =
                    study.split().queriesIn(study.queries(), part);
            ObjectNode partNode = split.putObject(part.csvValue());
            partNode.put("complaints", partQueries.stream()
                    .filter(query -> query.kind() == EvaluationQuery.Kind.COMPLAINT)
                    .count());
            partNode.put("negatives", partQueries.stream()
                    .filter(query -> query.kind() == EvaluationQuery.Kind.NEGATIVE)
                    .count());
        }

        ObjectNode grid = root.putObject("grid");
        grid.put("categoryWeightMin", 0.1);
        grid.put("categoryWeightMax", 0.7);
        grid.put("categoryWeightStep", 0.1);
        grid.put("thresholdMin", 0.20);
        grid.put("thresholdMax", 0.70);
        grid.put("thresholdStep", 0.025);
        grid.put("configurations", study.calibrationAggregates().size());
        grid.put("fieldWeights", "0.35/0.15/0.35/0.15");
        grid.put("limit", 5);

        root.set("baselineConfiguration", configNode(study.baseline()));
        root.set("selectedConfiguration", configNode(study.selected()));
        root.put(
                "decision",
                gate.passed() ? "APPLY_SELECTED" : "KEEP_BASELINE");
        root.set(
                "appliedConfiguration",
                configNode(gate.passed() ? study.selected() : study.baseline()));
        root.set("rollbackConfiguration", configNode(study.baseline()));

        ObjectNode calibration = root.putObject("calibration");
        calibration.set("baseline", aggregateNode(calibrationBaseline));
        calibration.set("selected", aggregateNode(calibrationSelected));
        calibration.put(
                "selectionRule",
                "negative failures <= baseline; then max mean precision@5; "
                        + "then mean returned precision; then fewer negative failures; "
                        + "then grid distance to baseline");

        ObjectNode test = root.putObject("test");
        test.set("baseline", aggregateNode(testBaseline));
        test.set("selected", aggregateNode(testSelected));
        ObjectNode gateNode = test.putObject("gate");
        gateNode.put("precisionAt5Increased", gate.precisionAt5Increased());
        gateNode.put(
                "returnedPrecisionNotLower", gate.returnedPrecisionNotLower());
        gateNode.put(
                "negativeFailuresNotIncreased", gate.negativeFailuresNotIncreased());
        gateNode.put("passed", gate.passed());

        root.put("exploratoryJudgments", study.labels().size());
        root.put("humanJudgments", 0);

        ArrayNode limitations = root.putArray("limitations");
        limitations.add("Relevance labels were assigned by an automated exploratory review, not by humans.");
        limitations.add("The 500-case sample does not represent all CFPB complaints.");
        limitations.add("Recall is not reported because the full corpus is not exhaustively labeled.");
        limitations.add("Similarity and historical responses do not prove successful handling.");

        String json = JSON.writerWithDefaultPrettyPrinter()
                .writeValueAsString(root)
                .replace("\r\n", "\n")
                + "\n";
        Files.writeString(path, json, StandardCharsets.UTF_8);
    }

    private static ObjectNode configNode(EvaluationConfig config) {
        ObjectNode node = JSON.createObjectNode();
        node.put("categoryWeight", config.categoryWeight());
        node.put("textWeight", config.textWeight());
        node.put("threshold", config.threshold());
        node.put("label", config.label());
        return node;
    }

    private static ObjectNode aggregateNode(EvaluationMetrics.Aggregate aggregate) {
        ObjectNode node = JSON.createObjectNode();
        node.put("complaintQueries", aggregate.complaintQueries());
        node.put("negativeQueries", aggregate.negativeQueries());
        node.put("meanPrecisionAt5", aggregate.meanPrecisionAt5());
        node.put("meanReturnedPrecision", aggregate.meanReturnedPrecision());
        node.put("complaintReturned", aggregate.complaintReturned());
        node.put("negativeReturned", aggregate.negativeReturned());
        node.put("totalReturned", aggregate.totalReturned());
        node.put("negativeFailures", aggregate.negativeFailures());
        node.put("negativeFailureRate", aggregate.negativeFailureRate());
        return node;
    }

    private static void writeMarkdown(
            Path path,
            EvaluationStudy study,
            EvaluationMetrics.Aggregate calibrationBaseline,
            EvaluationMetrics.Aggregate calibrationSelected,
            EvaluationMetrics.Aggregate testBaseline,
            EvaluationMetrics.Aggregate testSelected,
            EvaluationSelection.Gate gate) throws IOException {
        StringBuilder markdown = new StringBuilder();
        markdown.append("# Hasil kalibrasi pencarian eksploratif — 27 Oktober 2026\n\n");
        markdown.append("> Status: **eksploratif**. Label relevansi dibuat otomatis oleh peninjauan ")
                .append("berbantuan AI dan **belum divalidasi manusia**. Angka di bawah bukan ")
                .append("klaim kualitas final dan tidak boleh dipakai sebagai bukti keberhasilan ")
                .append("penanganan.\n\n");
        markdown.append("| Properti | Nilai |\n| --- | --- |\n");
        markdown.append("| Versi algoritme | `").append(TextRules.ALGORITHM_VERSION)
                .append("` |\n");
        markdown.append("| Sampel | ").append(study.sampleRecordCount())
                .append(" kasus, SHA-256 `").append(study.sampleSha256()).append("` |\n");
        markdown.append("| Seed pembagian | ").append(EvaluationSplit.SEED).append(" |\n");
        markdown.append("| Baseline | ").append(study.baseline().label())
                .append(" (kategori/teks 0.4/0.6, ambang 0.50) |\n");
        markdown.append("| Konfigurasi terpilih | ").append(study.selected().label())
                .append(" |\n");
        markdown.append("| Keputusan | **")
                .append(gate.passed() ? "terapkan konfigurasi terpilih" : "pertahankan baseline")
                .append("** |\n\n");

        markdown.append("## Dataset dan penilaian\n\n");
        markdown.append("Sebanyak 24 query dibekukan sebelum eksperimen: 16 skenario pengaduan ")
                .append("dan 8 skenario negatif. Pembagian terstratifikasi memakai seed `")
                .append(EvaluationSplit.SEED).append("`: 10 pengaduan + 4 negatif untuk ")
                .append("kalibrasi, dan 6 pengaduan + 4 negatif untuk pengujian akhir. Kandidat ")
                .append("top-5 dari seluruh 147 konfigurasi digabung dan setiap pasangan ")
                .append("query/kasus dinilai sekali tanpa skor, peringkat, atau identitas ")
                .append("konfigurasi. Precision@5 memakai penyebut tetap 5; slot kosong dihitung ")
                .append("tidak relevan. Precision hasil yang dikembalikan didefinisikan 0 bila ")
                .append("tidak ada hasil. Recall tidak dilaporkan karena korpus tidak dilabeli ")
                .append("menyeluruh.\n\n");

        markdown.append("## Kalibrasi (pemilihan hanya dari bagian ini)\n\n");
        markdown.append(comparisonTable(calibrationBaseline, calibrationSelected));
        markdown.append("\nKonfigurasi terpilih: **").append(study.selected().label())
                .append("**. Tabel lengkap 147 konfigurasi ada di ")
                .append("`calibration-configurations.csv`.\n\n");

        markdown.append("## Pengujian akhir (sekali, tidak dipakai untuk memilih)\n\n");
        markdown.append(comparisonTable(testBaseline, testSelected));
        markdown.append("\nGerbang penerapan: precision@5 meningkat = **")
                .append(gate.precisionAt5Increased() ? "ya" : "tidak")
                .append("**, precision hasil tidak turun = **")
                .append(gate.returnedPrecisionNotLower() ? "ya" : "tidak")
                .append("**, kegagalan negatif tidak bertambah = **")
                .append(gate.negativeFailuresNotIncreased() ? "ya" : "tidak")
                .append("**. Hasil: **")
                .append(gate.passed() ? "LULUS" : "GAGAL")
                .append("**.\n\n");

        markdown.append("## Contoh hasil pada bagian pengujian\n\n");
        appendExamples(markdown, study);

        markdown.append("## Batas dan keputusan\n\n");
        markdown.append("- Label dibuat otomatis dan belum divalidasi manusia; `judgments.csv` tetap ")
                .append("disediakan untuk penilaian manusia.\n");
        markdown.append("- Sampel 500 kasus hanya mencakup dua produk dan tidak mewakili seluruh CFPB.\n");
        markdown.append("- Kandidat dinilai pada penggabungan top-5 semua konfigurasi, sehingga ")
                .append("perbandingan konfigurasi tetap terbatas pada kandidat tersebut.\n");
        markdown.append("- Tidak ada klaim recall, akurasi, atau keberhasilan penanganan.\n");
        if (gate.passed()) {
            markdown.append("- Konfigurasi terpilih diterapkan ke default. Nilai sebelumnya ")
                    .append(study.baseline().label())
                    .append(" dicatat untuk rollback.\n");
        } else {
            markdown.append("- Konfigurasi terpilih **tidak** diterapkan; default baseline ")
                    .append("dipertahankan dan keterbatasan didokumentasikan.\n");
        }
        Files.writeString(path, markdown.toString(), StandardCharsets.UTF_8);
    }

    private static String comparisonTable(
            EvaluationMetrics.Aggregate baseline,
            EvaluationMetrics.Aggregate selected) {
        return "| Metrik | Baseline | Terpilih |\n"
                + "| --- | ---: | ---: |\n"
                + row("Rata-rata precision@5", baseline.meanPrecisionAt5(),
                        selected.meanPrecisionAt5())
                + row("Rata-rata precision hasil", baseline.meanReturnedPrecision(),
                        selected.meanReturnedPrecision())
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

    private static void appendExamples(StringBuilder markdown, EvaluationStudy study) {
        Map<String, EvaluationCandidates.EvaluationCandidate> candidates = new LinkedHashMap<>();
        for (EvaluationCandidates.EvaluationCandidate candidate : study.candidates()) {
            candidates.put(candidate.queryId() + "/" + candidate.cfpbId(), candidate);
        }
        List<EvaluationQuery> testComplaints = study.split().queriesIn(
                        study.queries(), EvaluationSplit.Part.TEST).stream()
                .filter(query -> query.kind() == EvaluationQuery.Kind.COMPLAINT)
                .sorted(Comparator.comparing(EvaluationQuery::queryId))
                .toList();
        EvaluationQuery success = null;
        EvaluationQuery failure = null;
        int best = -1;
        int worst = Integer.MAX_VALUE;
        for (EvaluationQuery query : testComplaints) {
            List<SimilarityResult> results =
                    study.testSelectedResults().get(query.queryId());
            int relevant = relevantCount(query, results, study.labels());
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
        appendQueryExample(markdown, study, candidates, success);
        markdown.append("\n### Kegagalan\n\n");
        appendQueryExample(markdown, study, candidates, failure);
        for (EvaluationQuery query : study.split().queriesIn(
                study.queries(), EvaluationSplit.Part.TEST)) {
            if (query.kind() != EvaluationQuery.Kind.NEGATIVE) {
                continue;
            }
            List<SimilarityResult> results = study.testSelectedResults().get(query.queryId());
            if (results.isEmpty()) {
                continue;
            }
            markdown.append("\n**Negatif ").append(query.queryId()).append("** (")
                    .append(query.scenario()).append(") tetap mendapat hasil:\n\n");
            for (SimilarityResult result : results) {
                String cfpbId = EvaluationCaseIds.complaintId(result.caseRecord());
                markdown.append("- `").append(cfpbId).append("` — ")
                        .append(study.labels().reason(query.queryId(), cfpbId))
                        .append("\n");
            }
        }
    }

    private static void appendQueryExample(
            StringBuilder markdown,
            EvaluationStudy study,
            Map<String, EvaluationCandidates.EvaluationCandidate> candidates,
            EvaluationQuery query) {
        if (query == null) {
            markdown.append("Tidak ada query pengaduan pada bagian pengujian.\n");
            return;
        }
        markdown.append("**").append(query.queryId()).append("** — ")
                .append(query.scenario()).append("\n\n");
        markdown.append("> ").append(query.narrative()).append("\n\n");
        List<SimilarityResult> results = study.testSelectedResults().get(query.queryId());
        if (results.isEmpty()) {
            markdown.append("Tidak ada hasil.\n");
            return;
        }
        for (SimilarityResult result : results) {
            String cfpbId = EvaluationCaseIds.complaintId(result.caseRecord());
            boolean relevant = study.labels().relevant(query.queryId(), cfpbId);
            EvaluationCandidates.EvaluationCandidate candidate =
                    candidates.get(query.queryId() + "/" + cfpbId);
            String narrative = candidate == null
                    ? result.caseRecord().narrative()
                    : candidate.caseNarrative();
            markdown.append("- `").append(cfpbId).append("` — **")
                    .append(relevant ? "relevan" : "tidak relevan").append("**: ")
                    .append(study.labels().reason(query.queryId(), cfpbId))
                    .append("  \n  _").append(excerpt(narrative, 260)).append("_\n");
        }
    }

    private static int relevantCount(
            EvaluationQuery query,
            List<SimilarityResult> results,
            EvaluationLabels labels) {
        int count = 0;
        for (SimilarityResult result : results) {
            String cfpbId = EvaluationCaseIds.complaintId(result.caseRecord());
            if (labels.relevant(query.queryId(), cfpbId)) {
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

    private record Role(
            String name,
            EvaluationConfig config,
            Map<String, List<SimilarityResult>> results) {
    }

    /** Prints the stored summary for the compare command. */
    public static String compareSummary(Path summaryJson) throws IOException {
        JsonNode root = JSON.readTree(Files.readString(summaryJson, StandardCharsets.UTF_8));
        StringBuilder output = new StringBuilder();
        output.append("Keputusan: ").append(root.get("decision").asString()).append('\n');
        output.append("Baseline: ")
                .append(root.get("baselineConfiguration").get("label").asString())
                .append('\n');
        output.append("Terpilih: ")
                .append(root.get("selectedConfiguration").get("label").asString())
                .append("\n\n");
        appendAggregate(output, "Kalibrasi baseline", root.get("calibration").get("baseline"));
        appendAggregate(output, "Kalibrasi terpilih", root.get("calibration").get("selected"));
        appendAggregate(output, "Uji baseline", root.get("test").get("baseline"));
        appendAggregate(output, "Uji terpilih", root.get("test").get("selected"));
        return output.toString();
    }

    private static void appendAggregate(
            StringBuilder output, String name, JsonNode aggregate) {
        output.append(name)
                .append(": P@5=")
                .append(String.format(Locale.ROOT, "%.3f", aggregate.get("meanPrecisionAt5").asDouble()))
                .append(", precision hasil=")
                .append(String.format(
                        Locale.ROOT, "%.3f", aggregate.get("meanReturnedPrecision").asDouble()))
                .append(", hasil=")
                .append(aggregate.get("totalReturned").asInt())
                .append(", negatif gagal=")
                .append(aggregate.get("negativeFailures").asInt())
                .append('\n');
    }
}
