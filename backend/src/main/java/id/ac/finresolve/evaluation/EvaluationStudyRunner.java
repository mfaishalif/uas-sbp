package id.ac.finresolve.evaluation;

import id.ac.finresolve.domain.similarity.SimilarityMode;
import id.ac.finresolve.domain.similarity.SimilarityResult;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Loads and validates one manifest-driven evaluation study. */
public final class EvaluationStudyRunner {

    private final EvaluationManifest manifest;
    private final List<EvaluationQuery> queries;
    private final EvaluationSplit split;
    private final EvaluationCorpus corpus;
    private final EvaluationConfig.EvaluationGrid grid;
    private final List<EvaluationConfig> configs;
    private final EvaluationRunner runner;

    private EvaluationStudyRunner(
            EvaluationManifest manifest,
            List<EvaluationQuery> queries,
            EvaluationSplit split,
            EvaluationCorpus corpus) {
        this.manifest = manifest;
        this.queries = queries;
        this.split = split;
        this.corpus = corpus;
        this.grid = manifest.grid();
        this.configs = EvaluationConfig.grid(grid);
        this.runner = new EvaluationRunner(corpus, grid);
    }

    public static EvaluationStudyRunner load(EvaluationManifest manifest) {
        List<EvaluationQuery> queries = EvaluationQueries.load(manifest.queries());
        EvaluationSplit expected = EvaluationSplit.create(queries, manifest.splitSpec());
        EvaluationSplit split = EvaluationSplitFile.verify(
                manifest.split(), queries, expected);
        EvaluationCorpus corpus = EvaluationCorpus.load(
                manifest.sampleCsv(), manifest.sampleManifest());
        if (manifest.dictionarySha256() != null
                && !manifest.dictionarySha256().equals(EvaluationChecksums.dictionary())) {
            throw new IllegalArgumentException(
                    "Phrase dictionary checksum does not match the study manifest");
        }
        EvaluationStudyRunner study = new EvaluationStudyRunner(
                manifest, queries, split, corpus);
        study.validateCounts();
        return study;
    }

    private void validateCounts() {
        EvaluationManifest.Expected expected = manifest.expected();
        int complaints = count(EvaluationQuery.Kind.COMPLAINT, null);
        int negatives = count(EvaluationQuery.Kind.NEGATIVE, null);
        int calibration = split.count(EvaluationSplit.Part.CALIBRATION);
        int test = split.count(EvaluationSplit.Part.TEST);
        int calibrationComplaints = split.count(
                EvaluationSplit.Part.CALIBRATION,
                EvaluationQuery.Kind.COMPLAINT,
                queries);
        int calibrationNegatives = split.count(
                EvaluationSplit.Part.CALIBRATION,
                EvaluationQuery.Kind.NEGATIVE,
                queries);
        int testComplaints = split.count(
                EvaluationSplit.Part.TEST, EvaluationQuery.Kind.COMPLAINT, queries);
        int testNegatives = split.count(
                EvaluationSplit.Part.TEST, EvaluationQuery.Kind.NEGATIVE, queries);
        int studyGroups = (int) queries.stream()
                .filter(query -> query.cohort() == EvaluationQuery.Cohort.STUDY)
                .map(EvaluationQuery::groupId)
                .distinct()
                .count();

        require(queries.size() == expected.total(), "total query count");
        require(complaints == expected.complaints(), "complaint query count");
        require(negatives == expected.negatives(), "negative query count");
        require(calibration == expected.calibrationTotal(), "calibration query count");
        require(calibrationComplaints == expected.calibrationComplaints(),
                "calibration complaint count");
        require(calibrationNegatives == expected.calibrationNegatives(),
                "calibration negative count");
        require(test == expected.testTotal(), "test query count");
        require(testComplaints == expected.testComplaints(), "test complaint count");
        require(testNegatives == expected.testNegatives(), "test negative count");
        require(studyGroups == expected.studyGroups(), "study group count");

        for (EvaluationQuery query : queries) {
            if (query.cohort() == EvaluationQuery.Cohort.DEVELOPMENT
                    && split.partOf(query.queryId()) != EvaluationSplit.Part.CALIBRATION) {
                throw new IllegalArgumentException(
                        "Development query must remain in calibration: " + query.queryId());
            }
        }
    }

    private static void require(boolean condition, String label) {
        if (!condition) {
            throw new IllegalArgumentException(
                    "Study manifest count mismatch: " + label);
        }
    }

    private int count(EvaluationQuery.Kind kind, EvaluationSplit.Part part) {
        return (int) queries.stream()
                .filter(query -> kind == null || query.kind() == kind)
                .filter(query -> part == null || split.partOf(query.queryId()) == part)
                .count();
    }

    public List<EvaluationQuery> queriesIn(EvaluationSplit.Part part) {
        return split.queriesIn(queries, part);
    }

    public Map<String, List<SimilarityResult>> rank(
            EvaluationSplit.Part part, EvaluationConfig config) {
        return runner.rank(queriesIn(part), config);
    }

    public Map<EvaluationConfig, Map<String, List<SimilarityResult>>> rank(
            EvaluationSplit.Part part, List<EvaluationConfig> selectedConfigs) {
        return runner.rankAll(queriesIn(part), selectedConfigs);
    }

    public Map<EvaluationConfig, EvaluationMetrics.Aggregate> aggregates(
            EvaluationSplit.Part part,
            Map<EvaluationConfig, Map<String, List<SimilarityResult>>> results,
            EvaluationLabels labels) {
        List<EvaluationQuery> queriesInPart = queriesIn(part);
        Map<EvaluationConfig, EvaluationMetrics.Aggregate> aggregates =
                new LinkedHashMap<>();
        for (Map.Entry<EvaluationConfig, Map<String, List<SimilarityResult>>> entry
                : results.entrySet()) {
            aggregates.put(
                    entry.getKey(),
                    EvaluationMetrics.aggregate(queriesInPart, entry.getValue(), labels));
        }
        return Map.copyOf(aggregates);
    }

    public EvaluationManifest manifest() {
        return manifest;
    }

    public List<EvaluationQuery> queries() {
        return queries;
    }

    public EvaluationSplit split() {
        return split;
    }

    public EvaluationCorpus corpus() {
        return corpus;
    }

    public EvaluationConfig.EvaluationGrid grid() {
        return grid;
    }

    public List<EvaluationConfig> configs() {
        return configs;
    }

    public EvaluationRunner runner() {
        return runner;
    }

    public List<EvaluationConfig> configsForModes(List<SimilarityMode> modes) {
        return configs.stream().filter(config -> modes.contains(config.mode())).toList();
    }
}
