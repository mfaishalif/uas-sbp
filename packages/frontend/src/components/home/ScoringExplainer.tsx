'use client';

import { useAppState } from '@/contexts/AppState';
import { fieldLabel, formatWeight } from '@/lib/format';
import { DEFAULT_SEARCH_CONFIGURATION, SEARCH_LIMIT, barWidth } from '@/lib/scoring';
import styles from './Sections.module.css';

const STATUSES = [
  { status: 'Match', meaning: 'The case has the same value (trimmed, case-insensitive). Earns the field’s weight.' },
  { status: 'Mismatch', meaning: 'The case has a different value. Earns nothing, but the weight still counts.' },
  { status: 'Missing in case', meaning: 'You filled the field; the case left it empty. Earns nothing.' },
  { status: 'Not used', meaning: 'You left the field empty, so it is excluded from the calculation.' },
];

/** The four category fields, in the order the server reports them. */
const FIELDS = ['product', 'subProduct', 'issue', 'subIssue'] as const;

export function ScoringExplainer() {
  const { searchConfiguration } = useAppState();
  const configuration = searchConfiguration ?? DEFAULT_SEARCH_CONFIGURATION;
  const live = searchConfiguration !== null;
  const cw = formatWeight(configuration.categoryWeight);
  const tw = formatWeight(configuration.textWeight);
  const threshold = formatWeight(configuration.threshold);
  const fieldWeights = configuration.categoryFieldWeights;
  const fieldTotal = FIELDS.reduce((sum, field) => sum + fieldWeights[field], 0) || 1;
  const twoFieldShare = formatWeight(
    fieldWeights.product / (fieldWeights.product + fieldWeights.issue || 1),
  );

  return (
    <section id="scoring" className={styles.sageBand} aria-labelledby="scoring-title">
      <div className="fin-container">
        <div className={styles.heading}>
          <p className="page-eyebrow">Explainable by design</p>
          <h2 className={styles.title} id="scoring-title">How matches are scored</h2>
          <p className={styles.lead}>
            Every number on a precedent card comes from the rules below.{' '}
            <span className={styles.configTag}>
              {live
                ? `Live configuration from your last search · ${configuration.algorithmVersion}`
                : `Showing the default configuration · ${configuration.algorithmVersion}`}
            </span>
          </p>
        </div>

        <div className={styles.scoringGrid}>
          <div className={styles.scoringCard}>
            <h3 className={styles.cardTitle}>Total score</h3>
            <p className={styles.formula}>
              total = <strong>{cw}</strong> × category + <strong>{tw}</strong> × text
            </p>
            <div className={styles.splitBar} role="img" aria-label={`Category weight ${cw}, text weight ${tw}`}>
              <span className={styles.splitInk} style={{ width: barWidth(configuration.categoryWeight) }}>
                Category {cw}
              </span>
              <span className={styles.splitLight} style={{ width: barWidth(configuration.textWeight) }}>
                Text {tw}
              </span>
            </div>
            <ul className={styles.rules}>
              <li>A case is a precedent only if its text score is above 0 <em>and</em> its total is at least <strong>{threshold}</strong>.</li>
              <li>There is no product filter: every case in the library, CFPB or Analyst, is a candidate.</li>
              <li>Candidates are ranked by total score and the top {SEARCH_LIMIT} are shown.</li>
            </ul>
          </div>

          <div className={styles.scoringCard}>
            <h3 className={styles.cardTitle}>Category score</h3>
            <div className={styles.fieldBar} role="img" aria-label={FIELDS.map((field) => `${fieldLabel(field)} ${formatWeight(fieldWeights[field])}`).join(', ')}>
              {FIELDS.map((field) => (
                <span
                  key={field}
                  className={field === 'product' || field === 'issue' ? styles.splitInk : styles.splitLight}
                  style={{ width: `${(fieldWeights[field] / fieldTotal) * 100}%` }}
                >
                  {formatWeight(fieldWeights[field])}
                </span>
              ))}
            </div>
            <p className={styles.legend}>
              {FIELDS.map((field) => `${fieldLabel(field)} ${formatWeight(fieldWeights[field])}`).join(' · ')}
            </p>
            <p className={styles.body}>
              Weights are normalized over the fields you fill in. With only product and
              issue, each counts {twoFieldShare}. The category score is the sum of the
              normalized weights of the fields that match.
            </p>
            <dl className={styles.statusList}>
              {STATUSES.map((item) => (
                <div key={item.status}>
                  <dt>{item.status}</dt>
                  <dd>{item.meaning}</dd>
                </div>
              ))}
            </dl>
          </div>

          <div className={styles.scoringCard}>
            <h3 className={styles.cardTitle}>Text score</h3>
            <p className={styles.body}>
              Cosine similarity between TF-IDF vectors of your narrative and the case
              narrative.
            </p>
            <p className={styles.formula}>
              idf(term) = ln((N + 1) ÷ (df + 1)) + 1
            </p>
            <ul className={styles.rules}>
              <li><strong>N</strong> is the number of cases, <strong>df</strong> the number of narratives containing the term. Rare words weigh more.</li>
              <li>Term weight = raw count × idf. Both vectors are L2-normalized, so the dot product is the cosine.</li>
              <li>The tokenizer lowercases text and drops stopwords, numbers, one-character tokens, and redaction placeholders such as XX and XXXX.</li>
              <li>The vocabulary is refitted on every search, so newly saved cases count immediately.</li>
              <li>Up to 10 shared terms are listed, strongest contribution first.</li>
            </ul>
          </div>

          <div className={styles.scoringCard}>
            <h3 className={styles.cardTitle}>Response recommendation</h3>
            <p className={styles.formula}>
              share(r) = Σ total score of precedents with response r ÷ Σ total score of
              all precedents shown × 100%
            </p>
            <ul className={styles.rules}>
              <li>Each precedent votes for its company response with its total score.</li>
              <li>The highest share is marked <strong>Recommended</strong>.</li>
              <li>On a tie, the response of the highest-scoring precedent wins.</li>
              <li>With no precedents there is no recommendation and every bar is 0%.</li>
            </ul>
          </div>
        </div>
      </div>
    </section>
  );
}
