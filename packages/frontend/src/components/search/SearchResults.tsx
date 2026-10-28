'use client';

import Link from 'next/link';
import type {
  CategoryExplanation,
  SearchConfiguration,
  SearchMatch,
  SearchResponse,
} from '@finresolve/api-client';
import { Badge } from '@/components/ui/Badge';
import { Button } from '@/components/ui/Button';
import { Card } from '@/components/ui/Card';
import { EmptyState } from '@/components/ui/EmptyState';
import { LinkButton } from '@/components/ui/LinkButton';
import { StatusMessage } from '@/components/ui/StatusMessage';
import {
  categoryReasonLabel,
  fieldLabel,
  formatScore,
  formatWeight,
  sourceLabel,
  truncate,
} from '@/lib/format';
import styles from './SearchResults.module.css';

export interface SearchResultsProps {
  response: SearchResponse;
  selectedReferenceIds: Set<string>;
  onToggleReference: (match: SearchMatch) => void;
  onContinue: () => void;
}

function reasonTone(status: string): 'positive' | 'negative' | 'missing' | 'neutral' {
  switch (status) {
    case 'MATCH':
      return 'positive';
    case 'MISMATCH':
      return 'negative';
    case 'MISSING_IN_CASE':
      return 'missing';
    default:
      return 'neutral';
  }
}

function CategoryReasons({ reasons }: { reasons: CategoryExplanation[] }) {
  return (
    <div className={styles.reasons}>
      <h4 className={styles.subheading}>Category reasons</h4>
      <ul className={styles.reasonList}>
        {reasons.map((reason) => (
          <li key={reason.field} className={styles.reason}>
            <div className={styles.reasonHead}>
              <span className={styles.reasonField}>{fieldLabel(reason.field)}</span>
              <Badge tone={reasonTone(reason.status)}>
                {categoryReasonLabel(reason.status)}
              </Badge>
            </div>
            <dl className={styles.reasonValues}>
              <div>
                <dt>Search</dt>
                <dd>{reason.queryValue ?? '—'}</dd>
              </div>
              <div>
                <dt>Case</dt>
                <dd>{reason.caseValue ?? '—'}</dd>
              </div>
              <div>
                <dt>Weight</dt>
                <dd>
                  {formatWeight(reason.effectiveWeight)}
                  <span className={styles.muted}>
                    {' '}
                    of {formatWeight(reason.configuredWeight)}
                  </span>
                </dd>
              </div>
              <div>
                <dt>Contribution</dt>
                <dd>{formatScore(reason.contribution)}</dd>
              </div>
            </dl>
          </li>
        ))}
      </ul>
    </div>
  );
}

function Configuration({ configuration }: { configuration: SearchConfiguration }) {
  return (
    <section className={styles.configuration} aria-labelledby="search-configuration">
      <h3 className={styles.configTitle} id="search-configuration">
        Actual server configuration
      </h3>
      <dl className={styles.configGrid}>
        <div>
          <dt>Algorithm</dt>
          <dd><code>{configuration.algorithmVersion}</code></dd>
        </div>
        <div>
          <dt>Category weight</dt>
          <dd>{formatWeight(configuration.categoryWeight)}</dd>
        </div>
        <div>
          <dt>Text weight</dt>
          <dd>{formatWeight(configuration.textWeight)}</dd>
        </div>
        <div>
          <dt>Threshold</dt>
          <dd>{formatWeight(configuration.threshold)}</dd>
        </div>
        <div>
          <dt>Text overlap required</dt>
          <dd>{configuration.requiresTextOverlap ? 'Yes' : 'No'}</dd>
        </div>
        <div>
          <dt>Field weights</dt>
          <dd>
            {Object.entries(configuration.categoryFieldWeights)
              .map(([field, weight]) => `${fieldLabel(field)} ${formatWeight(weight)}`)
              .join(' · ')}
          </dd>
        </div>
      </dl>
    </section>
  );
}

function MatchCard({
  match,
  selected,
  onToggleReference,
}: {
  match: SearchMatch;
  selected: boolean;
  onToggleReference: () => void;
}) {
  const caseRecord = match._case;
  return (
    <Card as="article" className={styles.match}>
      <div className={styles.matchHeader}>
        <div className={styles.matchIdentity}>
          <div className={styles.badges}>
            <Badge tone={caseRecord.source === 'CFPB' ? 'neutral' : 'ink'}>
              {sourceLabel(caseRecord.source)}
            </Badge>
            {caseRecord.company ? (
              <span className={styles.company}>{caseRecord.company}</span>
            ) : null}
          </div>
          <h3 className={styles.matchTitle}>
            {caseRecord.product}
            <span className={styles.matchIssue}> · {caseRecord.issue}</span>
          </h3>
          {caseRecord.subProduct || caseRecord.subIssue ? (
            <p className={styles.matchPath}>
              {[caseRecord.subProduct, caseRecord.subIssue].filter(Boolean).join(' / ')}
            </p>
          ) : null}
        </div>
        <div className={styles.scoreBlock}>
          <p className={styles.score}>{formatScore(match.score)}</p>
          <p className={styles.scoreLabel}>Similarity score</p>
        </div>
      </div>

      <p className={styles.preview}>{truncate(caseRecord.narrative, 320)}</p>

      <div className={styles.scoreGrid}>
        <div>
          <span className={styles.scoreGridValue}>{formatScore(match.categoryScore)}</span>
          <span className={styles.scoreGridLabel}>Category score</span>
        </div>
        <div>
          <span className={styles.scoreGridValue}>{formatScore(match.textScore)}</span>
          <span className={styles.scoreGridLabel}>Text score</span>
        </div>
      </div>

      <CategoryReasons reasons={match.categoryExplanation} />

      <div className={styles.terms}>
        <h4 className={styles.subheading}>Shared terms</h4>
        {match.sharedTerms.length ? (
          <ul className={styles.termList}>
            {match.sharedTerms.map((term) => (
              <li key={term.term} className={styles.term}>
                <span>{term.term}</span>
                <span className={styles.termScore}>{formatScore(term.contribution)}</span>
              </li>
            ))}
          </ul>
        ) : (
          <p className={styles.muted}>No shared terms were returned.</p>
        )}
      </div>

      <div className={styles.matchActions}>
        <Button
          variant={selected ? 'secondary' : 'primary'}
          aria-pressed={selected}
          onClick={onToggleReference}
        >
          {selected ? 'Remove reference' : 'Use as reference'}
        </Button>
        <LinkButton href={`/cases/${caseRecord.id}`} variant="tertiary">
          View full case
        </LinkButton>
      </div>
    </Card>
  );
}

export function SearchResults({
  response,
  selectedReferenceIds,
  onToggleReference,
  onContinue,
}: SearchResultsProps) {
  const selectedCount = response.results.filter((match) =>
    Array.from(selectedReferenceIds).some(
      (id) => id.toLowerCase() === match._case.id.toLowerCase(),
    ),
  ).length;

  return (
    <div className={styles.results} aria-live="polite">
      <div className={styles.resultsHeading}>
        <div>
          <p className="page-eyebrow">Search results</p>
          <h2 className={styles.resultsTitle}>
            {response.status === 'MATCHES' ? 'Precedent matches' : 'Search outcome'}
          </h2>
        </div>
        <span className={styles.corpus}>
          {response.corpusSize.toLocaleString('en-US')} cases in the current corpus
        </span>
      </div>

      {response.warnings.map((warning, index) => (
        <StatusMessage
          key={`${index}-${warning}`}
          tone="warning"
          title="Server warning"
        >
          <p>{warning}</p>
        </StatusMessage>
      ))}

      {response.status === 'MATCHES' ? (
        <>
          <div className={styles.selectionBar}>
            <p>
              {response.returnedCount} of {response.limit} possible precedents shown.
              {' '}
              {selectedCount
                ? `${selectedCount} selected as ${selectedCount === 1 ? 'a reference' : 'references'}.`
                : 'Select any precedents you want to keep as references.'}
            </p>
            <Button onClick={onContinue}>Continue to retention</Button>
          </div>
          <div className={styles.matchList}>
            {response.results.map((match) => (
              <MatchCard
                key={match._case.id}
                match={match}
                selected={Array.from(selectedReferenceIds).some(
                  (id) => id.toLowerCase() === match._case.id.toLowerCase(),
                )}
                onToggleReference={() => onToggleReference(match)}
              />
            ))}
          </div>
        </>
      ) : null}

      {response.status === 'NO_RELEVANT_CASES' ? (
        <EmptyState
          title="No relevant precedents found"
          action={<Link href="/imports">Review the imported corpus</Link>}
        >
          <p>
            Nothing in the current knowledge base crossed the relevance threshold.
            Similarity is context, not certainty. Refine the complaint details or
            handle this case without a precedent.
          </p>
        </EmptyState>
      ) : null}

      {response.status === 'EMPTY_KNOWLEDGE_BASE' ? (
        <EmptyState
          title="The knowledge base is empty"
          action={<LinkButton href="/imports">Import CFPB data</LinkButton>}
        >
          <p>
            No cases have been imported yet. Import the CFPB CSV archive before
            searching for precedents.
          </p>
        </EmptyState>
      ) : null}

      <p className={styles.disclaimer}>
        Scores measure similarity between the complaint and a case. They are not a
        probability that a historical response will succeed.
      </p>

      <Configuration configuration={response._configuration} />
    </div>
  );
}
