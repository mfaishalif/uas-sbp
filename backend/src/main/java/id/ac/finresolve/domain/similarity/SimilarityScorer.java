package id.ac.finresolve.domain.similarity;

import id.ac.finresolve.domain.cases.CaseRecord;
import id.ac.finresolve.domain.cases.ComplaintInput;
import java.util.List;

/** Ranks an already-materialized case snapshot without touching the database. */
public interface SimilarityScorer {

    List<SimilarityResult> rank(
            ComplaintInput query,
            List<CaseRecord> cases,
            int limit,
            SimilaritySettings settings);
}
