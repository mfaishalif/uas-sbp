import type { SearchConfiguration, SearchMatch } from '@finresolve/api-client';

/** Number of precedents requested from POST /search. */
export const SEARCH_LIMIT = 3;

/**
 * Mirrors backend/src/main/resources/application.yml. Shown only until a search
 * returns the live configuration the server actually used.
 */
export const DEFAULT_SEARCH_CONFIGURATION: SearchConfiguration = {
  categoryWeight: 0.4,
  textWeight: 0.6,
  categoryFieldWeights: {
    product: 0.35,
    subProduct: 0.15,
    issue: 0.35,
    subIssue: 0.15,
  },
  threshold: 0.5,
  requiresTextOverlap: true,
  algorithmVersion: 'tfidf-cosine-v1',
};

export interface ScoreBreakdown {
  /** categoryWeight × categoryScore */
  category: number;
  /** textWeight × textScore */
  text: number;
}

export function scoreBreakdown(
  match: Pick<SearchMatch, 'categoryScore' | 'textScore'>,
  configuration: Pick<SearchConfiguration, 'categoryWeight' | 'textWeight'>,
): ScoreBreakdown {
  return {
    category: configuration.categoryWeight * match.categoryScore,
    text: configuration.textWeight * match.textScore,
  };
}

/** Converts a 0–1 value into a CSS width that never exceeds the track. */
export function barWidth(value: number): string {
  return `${Math.max(0, Math.min(1, value)) * 100}%`;
}
