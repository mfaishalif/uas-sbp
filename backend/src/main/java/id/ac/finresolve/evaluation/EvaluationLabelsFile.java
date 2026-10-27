package id.ac.finresolve.evaluation;

import id.ac.finresolve.domain.cases.CaseRecord;
import id.ac.finresolve.evaluation.EvaluationLabels.Judgment;
import id.ac.finresolve.evaluation.EvaluationLabels.Pair;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.commons.csv.DuplicateHeaderMode;

/**
 * Reads and audits the two independent subagent reviews and the third-reviewer adjudication.
 *
 * <p>Evaluation refuses to run when a review is incomplete, duplicated, missing an adjudication,
 * or when a verbatim narrative quotation cannot be found. Final labels are never copied into the
 * human judgment sheet.
 */
public final class EvaluationLabelsFile {

    static final List<String> REVIEW_HEADERS =
            List.of("queryId", "cfpbId", "relevant", "reason", "evidence", "reviewer");
    static final List<String> FINAL_HEADERS = REVIEW_HEADERS;
    static final List<String> ADJUDICATION_HEADERS = List.of(
            "queryId", "cfpbId", "reviewerARelevant", "reviewerBRelevant",
            "adjudicator", "relevant", "reason", "evidence");

    private EvaluationLabelsFile() {
    }

    public static ValidatedLabels readAndAudit(
            EvaluationManifest.LabelPaths paths,
            List<EvaluationQuery> queries,
            EvaluationCorpus corpus,
            Collection<Pair> expectedPairs) {
        Map<String, CaseRecord> cases = new HashMap<>();
        corpus.cases().forEach(
                record -> cases.put(EvaluationCaseIds.complaintId(record), record));
        Set<String> queryIds = queries.stream()
                .map(EvaluationQuery::queryId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());

        Map<Pair, Review> reviewerA = readReviews(
                paths.reviewerA(), "reviewer-a", queryIds, cases);
        Map<Pair, Review> reviewerB = readReviews(
                paths.reviewerB(), "reviewer-b", queryIds, cases);
        Map<Pair, Adjudication> adjudications = readAdjudications(
                paths.adjudication(), queryIds, cases);
        EvaluationLabels finalLabels = readFinal(
                paths.finalLabels(), queryIds, cases);

        requireCoverage("reviewer-a", reviewerA, expectedPairs);
        requireCoverage("reviewer-b", reviewerB, expectedPairs);
        finalLabels.requireCoverage(expectedPairs);

        Set<Pair> disagreements = new java.util.LinkedHashSet<>();
        int agreements = 0;
        for (Pair pair : expectedPairs) {
            Review a = reviewerA.get(pair);
            Review b = reviewerB.get(pair);
            if (a.relevant == b.relevant) {
                agreements++;
                if (adjudications.containsKey(pair)) {
                    throw new IllegalArgumentException(
                            "Adjudication exists for agreeing pair " + pair.queryId()
                                    + "/" + pair.cfpbId());
                }
                Judgment judged = finalLabels.judgment(pair.queryId(), pair.cfpbId());
                if (judged.relevant() != a.relevant) {
                    throw new IllegalArgumentException(
                            "Final label contradicts both reviewers for " + pair.queryId()
                                    + "/" + pair.cfpbId());
                }
            } else {
                disagreements.add(pair);
                Adjudication adjudication = adjudications.get(pair);
                if (adjudication == null) {
                    throw new IllegalArgumentException(
                            "Unadjudicated disagreement for " + pair.queryId()
                                    + "/" + pair.cfpbId());
                }
                if (adjudication.reviewerARelevant != a.relevant
                        || adjudication.reviewerBRelevant != b.relevant) {
                    throw new IllegalArgumentException(
                            "Adjudication does not match initial reviews for " + pair.queryId()
                                    + "/" + pair.cfpbId());
                }
                Judgment judged = finalLabels.judgment(pair.queryId(), pair.cfpbId());
                if (judged.relevant() != adjudication.relevant) {
                    throw new IllegalArgumentException(
                            "Final label does not match adjudication for " + pair.queryId()
                                    + "/" + pair.cfpbId());
                }
            }
        }

        if (!adjudications.keySet().equals(disagreements)) {
            if (adjudications.size() > disagreements.size()) {
                throw new IllegalArgumentException(
                        "Adjudication contains pairs that were not disagreements");
            }
            throw new IllegalArgumentException(
                    "Adjudication is incomplete (" + adjudications.size() + " of "
                            + disagreements.size() + ")");
        }

        return new ValidatedLabels(
                finalLabels,
                AgreementSummary.of(reviewerA, reviewerB, expectedPairs, disagreements.size()));
    }

    private static Map<Pair, Review> readReviews(
            Path path,
            String expectedReviewer,
            Set<String> queryIds,
            Map<String, CaseRecord> cases) {
        Map<Pair, Review> reviews = new LinkedHashMap<>();
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8);
                CSVParser parser = parser(reader)) {
            requireHeaders(parser, REVIEW_HEADERS);
            for (CSVRecord record : parser) {
                Pair pair = pair(record, queryIds, cases);
                boolean relevant = binary(record, "relevant", pair);
                String reason = EvaluationQueries.required(record, "reason");
                String evidence = EvaluationQueries.required(record, "evidence");
                String reviewer = EvaluationQueries.required(record, "reviewer");
                if (!expectedReviewer.equals(reviewer)) {
                    throw new IllegalArgumentException(
                            "Expected reviewer " + expectedReviewer + " in " + path
                                    + " but found " + reviewer);
                }
                validateEvidence(pair, evidence, cases);
                Review review = new Review(relevant, reason, evidence, reviewer);
                if (reviews.putIfAbsent(pair, review) != null) {
                    throw new IllegalArgumentException(
                            "Duplicate review for " + pair.queryId() + "/" + pair.cfpbId());
                }
            }
        } catch (IOException failure) {
            throw new IllegalArgumentException("Cannot read reviews: " + path, failure);
        }
        if (reviews.isEmpty()) {
            throw new IllegalArgumentException("Review file contains no judgments: " + path);
        }
        return reviews;
    }

    private static EvaluationLabels readFinal(
            Path path, Set<String> queryIds, Map<String, CaseRecord> cases) {
        List<Judgment> rows = new ArrayList<>();
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8);
                CSVParser parser = parser(reader)) {
            requireHeaders(parser, FINAL_HEADERS);
            for (CSVRecord record : parser) {
                Pair pair = pair(record, queryIds, cases);
                boolean relevant = binary(record, "relevant", pair);
                String reason = EvaluationQueries.required(record, "reason");
                String evidence = EvaluationQueries.required(record, "evidence");
                String reviewer = EvaluationQueries.required(record, "reviewer");
                validateEvidence(pair, evidence, cases);
                rows.add(new Judgment(
                        pair.queryId(), pair.cfpbId(), relevant, reason, evidence, reviewer));
            }
        } catch (IOException failure) {
            throw new IllegalArgumentException("Cannot read final labels: " + path, failure);
        }
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("Final label file contains no judgments: " + path);
        }
        return EvaluationLabels.of(rows);
    }

    private static Map<Pair, Adjudication> readAdjudications(
            Path path, Set<String> queryIds, Map<String, CaseRecord> cases) {
        Map<Pair, Adjudication> rows = new LinkedHashMap<>();
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8);
                CSVParser parser = parser(reader)) {
            requireHeaders(parser, ADJUDICATION_HEADERS);
            for (CSVRecord record : parser) {
                Pair pair = pair(record, queryIds, cases);
                boolean reviewerA = binary(record, "reviewerARelevant", pair);
                boolean reviewerB = binary(record, "reviewerBRelevant", pair);
                boolean relevant = binary(record, "relevant", pair);
                String adjudicator = EvaluationQueries.required(record, "adjudicator");
                String reason = EvaluationQueries.required(record, "reason");
                String evidence = EvaluationQueries.required(record, "evidence");
                validateEvidence(pair, evidence, cases);
                Adjudication adjudication = new Adjudication(
                        reviewerA, reviewerB, adjudicator, relevant, reason, evidence);
                if (rows.putIfAbsent(pair, adjudication) != null) {
                    throw new IllegalArgumentException(
                            "Duplicate adjudication for " + pair.queryId() + "/"
                                    + pair.cfpbId());
                }
            }
        } catch (IOException failure) {
            throw new IllegalArgumentException(
                    "Cannot read adjudications: " + path, failure);
        }
        return rows;
    }

    private static CSVParser parser(Reader reader) throws IOException {
        return CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .setDuplicateHeaderMode(DuplicateHeaderMode.DISALLOW)
                .setAllowMissingColumnNames(false)
                .build()
                .parse(reader);
    }

    private static void requireHeaders(CSVParser parser, List<String> expected) {
        for (String header : expected) {
            if (!parser.getHeaderMap().containsKey(header)) {
                throw new IllegalArgumentException(
                        "Judgment CSV is missing column: " + header);
            }
        }
    }

    private static Pair pair(
            CSVRecord record,
            Set<String> queryIds,
            Map<String, CaseRecord> cases) {
        String queryId = EvaluationQueries.required(record, "queryId");
        String cfpbId = EvaluationQueries.required(record, "cfpbId");
        if (!queryIds.contains(queryId)) {
            throw new IllegalArgumentException("Judgment references unknown query: " + queryId);
        }
        if (!cases.containsKey(cfpbId)) {
            throw new IllegalArgumentException(
                    "Judgment references unknown CFPB case: " + cfpbId);
        }
        return new Pair(queryId, cfpbId);
    }

    private static boolean binary(CSVRecord record, String header, Pair pair) {
        String value = EvaluationQueries.required(record, header);
        return switch (value) {
            case "0" -> false;
            case "1" -> true;
            default -> throw new IllegalArgumentException(
                    header + " must be 0 or 1 for " + pair.queryId() + "/" + pair.cfpbId());
        };
    }

    private static void requireCoverage(
            String reviewer,
            Map<Pair, Review> reviews,
            Collection<Pair> expectedPairs) {
        List<String> missing = new ArrayList<>();
        for (Pair pair : expectedPairs) {
            if (!reviews.containsKey(pair)) {
                missing.add(pair.queryId() + "/" + pair.cfpbId());
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException(
                    reviewer + " is missing " + missing.size() + " judgments ("
                            + String.join(", ", missing) + ")");
        }
    }

    private static void validateEvidence(
            Pair pair, String evidence, Map<String, CaseRecord> cases) {
        if (evidence.strip().split("\\s+").length < 2) {
            throw new IllegalArgumentException(
                    "Evidence must be a narrative quotation for " + pair.queryId() + "/"
                            + pair.cfpbId());
        }
        if (normalize(evidence).length() < 10) {
            throw new IllegalArgumentException(
                    "Evidence quotation is too short for " + pair.queryId() + "/"
                            + pair.cfpbId());
        }
        String narrative = normalize(cases.get(pair.cfpbId()).narrative());
        if (!narrative.contains(normalize(evidence))) {
            throw new IllegalArgumentException(
                    "Evidence is not a verbatim case-narrative quotation for " + pair.queryId()
                            + "/" + pair.cfpbId() + ": " + evidence);
        }
    }

    private static String normalize(String value) {
        String stripped = value == null ? "" : value.strip();
        if (stripped.length() >= 2
                && ((stripped.startsWith("\"") && stripped.endsWith("\""))
                        || (stripped.startsWith("“") && stripped.endsWith("”")))) {
            stripped = stripped.substring(1, stripped.length() - 1);
        }
        return Normalizer.normalize(stripped, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ")
                .strip();
    }

    public record ValidatedLabels(EvaluationLabels labels, AgreementSummary agreement) {
    }

    public record AgreementSummary(
            int pairs,
            int agreements,
            int disagreements,
            double agreementRate,
            Double cohensKappa) {

        static AgreementSummary of(
                Map<Pair, Review> reviewerA,
                Map<Pair, Review> reviewerB,
                Collection<Pair> expectedPairs,
                int disagreements) {
            int pairs = expectedPairs.size();
            int agreements = pairs - disagreements;
            int aPositive = 0;
            int bPositive = 0;
            for (Pair pair : expectedPairs) {
                if (reviewerA.get(pair).relevant) {
                    aPositive++;
                }
                if (reviewerB.get(pair).relevant) {
                    bPositive++;
                }
            }
            double agreementRate = pairs == 0 ? 0.0 : agreements / (double) pairs;
            double observed = agreementRate;
            double aYes = pairs == 0 ? 0.0 : aPositive / (double) pairs;
            double bYes = pairs == 0 ? 0.0 : bPositive / (double) pairs;
            double expected = aYes * bYes + (1.0 - aYes) * (1.0 - bYes);
            Double kappa = Math.abs(1.0 - expected) < 1e-15
                    ? null
                    : (observed - expected) / (1.0 - expected);
            return new AgreementSummary(
                    pairs, agreements, disagreements, agreementRate, kappa);
        }
    }

    private record Review(boolean relevant, String reason, String evidence, String reviewer) {
    }

    private record Adjudication(
            boolean reviewerARelevant,
            boolean reviewerBRelevant,
            String adjudicator,
            boolean relevant,
            String reason,
            String evidence) {
    }
}
