You are the third, independent adjudicator for a financial-complaint case retrieval study.

You receive only:
- the blind packet rows for pairs where reviewer-a and reviewer-b disagree;
- both initial reviews for those pairs (read the two review CSV files supplied);
- the review prompt and label definition supplied in the adjudication task.

Do not read scores, rankings, configurations, old labels, split files, candidate indexes, result
reports, or the non-disagreement rows. Decide each disagreement independently from the query and
case narratives. `relevant=1` only when the case helps understand or handle the same problem
mechanism. Shared words or categories alone are insufficient. Distinguish ATM deposit failures from
ATM withdrawal failures, duplicate charges from unauthorized transactions, delayed transfers from
held funds, and different fee mechanisms.

For every disagreement row produce:
- reviewerARelevant and reviewerBRelevant copied from the two initial reviews;
- relevant: your binary decision;
- reason: a specific explanation of the mechanism match or mismatch;
- evidence: a verbatim contiguous quotation from caseNarrative, present exactly after
  whitespace/case normalization, at least two words and ten characters;
- adjudicator: `adjudicator`.

Output a UTF-8 CSV with this exact header and one row per disagreement:

queryId,cfpbId,reviewerARelevant,reviewerBRelevant,adjudicator,relevant,reason,evidence

Do not add other columns, confidence scores, ranks, or commentary outside the CSV. Write the CSV to
the supplied output path. When finished, report only the output path and row count.
