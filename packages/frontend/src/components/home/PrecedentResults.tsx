'use client';

import type { CategoryExplanation, SearchMatch, SearchResponse } from '@finresolve/api-client';
import { Badge, type BadgeTone } from '@/components/ui/Badge';
import { Button } from '@/components/ui/Button';
import { Card } from '@/components/ui/Card';
import { EmptyState } from '@/components/ui/EmptyState';
import {
  categoryReasonLabel,
  fieldLabel,
  formatScore,
  formatWeight,
  sourceLabel,
  truncate,
} from '@/lib/format';
import { ScoreBar } from './ScoreBar';
import styles from './PrecedentResults.module.css';

function reasonTone(status: CategoryExplanation['status']): BadgeTone {
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

function PrecedentCard({
  match,
  rank,
  response,
  onOpenCase,
}: {
  match: SearchMatch;
  rank: number;
  response: SearchResponse;
  onOpenCase: (id: string) => void;
}) {
  const record = match._case;
  const titleId = `precedent-${rank}-title`;

  return (
    <Card as="article" className={styles.card} aria-labelledby={titleId}>
      <div className={styles.cardTop}>
        <div className={styles.identity}>
          <span className={styles.rank}>#{rank}</span>
          <Badge tone={record.source === 'CFPB' ? 'neutral' : 'ink'}>
            {sourceLabel(record.source)}
          </Badge>
        </div>
        <p className={styles.score}>
          <span className={styles.scoreValue}>{formatScore(match.score)}</span>
          <span className={styles.scoreLabel}>Total score</span>
        </p>
      </div>

      <h3 className={styles.cardTitle} id={titleId}>
        {record.product}
        <span className={styles.issue}> · {record.issue}</span>
      </h3>

      <ScoreBar
        categoryScore={match.categoryScore}
        textScore={match.textScore}
        configuration={response._configuration}
      />

      <div className={styles.block}>
        <h4 className={styles.blockTitle}>Category fields</h4>
        <ul className={styles.fieldList}>
          {match.categoryExplanation.map((reason) => (
            <li key={reason.field} className={styles.field}>
              <span className={styles.fieldName}>{fieldLabel(reason.field)}</span>
              <Badge tone={reasonTone(reason.status)} className={styles.fieldBadge}>
                {categoryReasonLabel(reason.status)}
              </Badge>
              <span className={styles.fieldValue}>
                {reason.caseValue ?? 'Not recorded'}
                {reason.status !== 'NOT_USED' ? (
                  <span className={styles.muted}>
                    {' '}· weight {formatWeight(reason.effectiveWeight)}
                  </span>
                ) : null}
              </span>
            </li>
          ))}
        </ul>
      </div>

      <div className={styles.block}>
        <h4 className={styles.blockTitle}>Shared terms</h4>
        {match.sharedTerms.length ? (
          <ul className={styles.terms} aria-label="Shared terms, strongest first">
            {match.sharedTerms.map((term) => (
              <li key={term.term} className={styles.term}>
                {term.term}
                <span className="visually-hidden"> contributes {formatScore(term.contribution)}</span>
              </li>
            ))}
          </ul>
        ) : (
          <p className={styles.muted}>No shared terms were returned.</p>
        )}
      </div>

      <div className={styles.block}>
        <h4 className={styles.blockTitle}>Narrative</h4>
        <p className={styles.narrative}>{truncate(record.narrative, 240)}</p>
      </div>

      <dl className={styles.outcome}>
        <div>
          <dt>Company</dt>
          <dd>{record.company ?? 'Not recorded'}</dd>
        </div>
        <div>
          <dt>Company response</dt>
          <dd className={styles.response}>{record.companyResponse ?? 'Not recorded'}</dd>
        </div>
      </dl>

      <Button variant="tertiary" onClick={() => onOpenCase(record.id)} className={styles.open}>
        View full case
        <span className="visually-hidden"> #{rank}</span>
      </Button>
    </Card>
  );
}

export function PrecedentResults({
  response,
  onOpenCase,
}: {
  response: SearchResponse;
  onOpenCase: (id: string) => void;
}) {
  const threshold = formatWeight(response._configuration.threshold);

  if (response.status === 'EMPTY_KNOWLEDGE_BASE') {
    return (
      <EmptyState title="The knowledge base is empty" headingLevel={3}>
        <p>
          No cases are loaded yet. The CFPB sample loads when the API starts with demo
          data enabled; restart the stack with <code>docker compose up</code> and search
          again.
        </p>
      </EmptyState>
    );
  }

  if (response.status === 'NO_RELEVANT_CASES' || response.results.length === 0) {
    return (
      <EmptyState title="No precedent cleared the threshold" headingLevel={3}>
        <p>
          None of the {response.corpusSize.toLocaleString('en-US')} cases shares enough
          meaningful words with this narrative to reach a total score of {threshold}.
          Add concrete details (what was charged, transferred, or refused), or handle
          this complaint without a precedent.
        </p>
      </EmptyState>
    );
  }

  return (
    <div className={styles.results}>
      <p className={styles.summary}>
        <strong>
          {response.returnedCount} of {response.corpusSize.toLocaleString('en-US')} cases
        </strong>{' '}
        are shown: the highest total scores at or above {threshold}.
      </p>
      <div className={styles.grid}>
        {response.results.map((match, index) => (
          <PrecedentCard
            key={match._case.id}
            match={match}
            rank={index + 1}
            response={response}
            onOpenCase={onOpenCase}
          />
        ))}
      </div>
      <p className={styles.disclaimer}>
        Similarity is context, not proof that a historical response will work. The
        calibration is exploratory and has not been validated by human review.
      </p>
    </div>
  );
}
