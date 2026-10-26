package id.ac.finresolve.similarity;

import id.ac.finresolve.cases.CaseRecord;
import id.ac.finresolve.cases.ComplaintInput;
import java.util.List;

/** Ranks an already-materialized case snapshot without touching the database. */
public interface SimilarityScorer {

    List<SimilarityResult> rank(
            ComplaintInput query,
            List<CaseRecord> cases,
            int limit,
            SimilaritySettings settings);
}
