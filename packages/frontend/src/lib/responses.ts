/**
 * Company responses offered when an analyst retains a case.
 *
 * The strings are copied exactly from the CFPB "Company response to consumer"
 * column so analyst cases and archive cases vote in the same buckets.
 */
export const COMPANY_RESPONSES = [
  'Closed with explanation',
  'Closed with monetary relief',
  'Closed with non-monetary relief',
] as const;

export type CompanyResponse = (typeof COMPANY_RESPONSES)[number];

export function isCompanyResponse(value: string | null | undefined): value is CompanyResponse {
  return (COMPANY_RESPONSES as readonly string[]).includes(value ?? '');
}

/** The minimum a precedent needs to take part in the vote. */
export interface ScoredPrecedent {
  score: number;
  _case: { companyResponse: string | null };
}

export interface ResponseShare {
  response: CompanyResponse;
  /** Sum of the total scores of the precedents that recorded this response. */
  weight: number;
  /** weight ÷ total weight of every precedent shown × 100. */
  percent: number;
  precedentCount: number;
}

export interface ResponseRecommendation {
  shares: ResponseShare[];
  recommended: CompanyResponse | null;
  /** Sum of the total scores of every precedent shown. */
  totalWeight: number;
  /** Share of the weight held by precedents whose response is not one of the three. */
  otherPercent: number;
}

const EPSILON = 1e-9;

/**
 * Similarity-weighted vote over the precedents on screen.
 *
 * percent(r) = Σ score of precedents with response r ÷ Σ score of all precedents × 100.
 * The highest percentage is recommended. A tie goes to the response held by the
 * highest-scoring precedent among the tied responses. With no precedents, or none
 * that recorded one of the three responses, nothing is recommended.
 */
export function recommendResponses(
  precedents: readonly ScoredPrecedent[],
): ResponseRecommendation {
  const totalWeight = precedents.reduce((sum, precedent) => sum + precedent.score, 0);

  const shares: ResponseShare[] = COMPANY_RESPONSES.map((response) => {
    const matching = precedents.filter((precedent) => precedent._case.companyResponse === response);
    const weight = matching.reduce((sum, precedent) => sum + precedent.score, 0);
    return {
      response,
      weight,
      percent: totalWeight > 0 ? (weight / totalWeight) * 100 : 0,
      precedentCount: matching.length,
    };
  });

  const knownWeight = shares.reduce((sum, share) => sum + share.weight, 0);
  const otherPercent = totalWeight > 0
    ? Math.max(0, ((totalWeight - knownWeight) / totalWeight) * 100)
    : 0;

  const best = Math.max(...shares.map((share) => share.weight));
  if (!(best > EPSILON)) {
    return { shares, recommended: null, totalWeight, otherPercent };
  }

  const tied = new Set(
    shares
      .filter((share) => Math.abs(share.weight - best) <= EPSILON)
      .map((share) => share.response),
  );

  let recommended: CompanyResponse | null = null;
  if (tied.size === 1) {
    recommended = [...tied][0];
  } else {
    // Stable sort keeps the server's order (score desc, then id) for equal scores.
    const byScore = [...precedents].sort((left, right) => right.score - left.score);
    const leader = byScore.find((precedent) => {
      const response = precedent._case.companyResponse;
      return isCompanyResponse(response) && tied.has(response);
    });
    recommended = leader && isCompanyResponse(leader._case.companyResponse)
      ? leader._case.companyResponse
      : null;
  }

  return { shares, recommended, totalWeight, otherPercent };
}

export function formatPercent(value: number): string {
  return `${value.toFixed(1)}%`;
}
