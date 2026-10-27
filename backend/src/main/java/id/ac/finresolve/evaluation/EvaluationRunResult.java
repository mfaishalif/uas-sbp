package id.ac.finresolve.evaluation;

import id.ac.finresolve.domain.similarity.SimilarityResult;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** All computed material needed to write the v2 evaluation artifacts. */
public record EvaluationRunResult(
        EvaluationStudyRunner study,
        EvaluationSelectionFile.FrozenSelection selection,
        EvaluationLabels calibrationLabels,
        EvaluationLabelsFile.AgreementSummary calibrationAgreement,
        EvaluationLabels finalLabels,
        EvaluationLabelsFile.AgreementSummary testAgreement,
        Map<EvaluationConfig, Map<String, java.util.List<SimilarityResult>>> calibrationRanking,
        Map<EvaluationConfig, EvaluationMetrics.Aggregate> calibrationAggregates,
        Map<String, java.util.List<SimilarityResult>> calibrationBaselineResults,
        Map<String, java.util.List<SimilarityResult>> calibrationSelectedResults,
        Map<String, java.util.List<SimilarityResult>> testBaselineResults,
        Map<String, java.util.List<SimilarityResult>> testSelectedResults,
        boolean testUsed,
        EvaluationConfig bestBaselineMode,
        EvaluationConfig bestPhrasesMode,
        Map<String, String> checksums,
        Map<String, String> packetChecksums) {

    public EvaluationRunResult {
        calibrationRanking = Map.copyOf(calibrationRanking);
        calibrationAggregates = Map.copyOf(calibrationAggregates);
        calibrationBaselineResults = Map.copyOf(calibrationBaselineResults);
        calibrationSelectedResults = Map.copyOf(calibrationSelectedResults);
        testBaselineResults = Map.copyOf(
                testBaselineResults == null ? Map.of() : testBaselineResults);
        testSelectedResults = Map.copyOf(
                testSelectedResults == null ? Map.of() : testSelectedResults);
        checksums = Collections.unmodifiableMap(new LinkedHashMap<>(checksums));
        packetChecksums = Collections.unmodifiableMap(new LinkedHashMap<>(packetChecksums));
    }

    public EvaluationConfig baseline() {
        return study.manifest().baseline();
    }

    public EvaluationConfig selected() {
        return selection.selected();
    }

    public EvaluationMetrics.Aggregate calibrationBaseline() {
        return calibrationAggregates.get(baseline());
    }

    public EvaluationMetrics.Aggregate calibrationSelected() {
        return calibrationAggregates.get(selected());
    }

    public EvaluationMetrics.Aggregate testBaseline() {
        if (!testUsed) {
            return null;
        }
        return EvaluationMetrics.aggregate(
                study.queriesIn(EvaluationSplit.Part.TEST),
                testBaselineResults,
                finalLabels);
    }

    public EvaluationMetrics.Aggregate testSelected() {
        if (!testUsed) {
            return null;
        }
        return EvaluationMetrics.aggregate(
                study.queriesIn(EvaluationSplit.Part.TEST),
                testSelectedResults,
                finalLabels);
    }

    public EvaluationSelection.Gate gate() {
        if (!testUsed) {
            return null;
        }
        return EvaluationSelection.gate(testBaseline(), testSelected());
    }

    public String decision() {
        if (!selection.precisionImproved()) {
            return "KEEP_BASELINE_CALIBRATION";
        }
        if (!testUsed) {
            return "KEEP_BASELINE_CALIBRATION";
        }
        return gate().passed() ? "APPLY_SELECTED" : "KEEP_BASELINE_TEST";
    }

    public EvaluationConfig appliedConfiguration() {
        return "APPLY_SELECTED".equals(decision()) ? selected() : baseline();
    }
}
