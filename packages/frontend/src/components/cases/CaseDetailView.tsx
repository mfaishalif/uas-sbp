'use client';

import Link from 'next/link';
import { useEffect, useRef, useState } from 'react';
import type { CaseDetail, CaseSummary } from '@finresolve/api-client';
import { Badge, type BadgeTone } from '@/components/ui/Badge';
import { Button } from '@/components/ui/Button';
import { Card } from '@/components/ui/Card';
import { LinkButton } from '@/components/ui/LinkButton';
import { LoadingState } from '@/components/ui/LoadingState';
import { StatusMessage } from '@/components/ui/StatusMessage';
import { useAppState } from '@/contexts/AppState';
import { getApiClient } from '@/lib/api';
import { categoryPath } from '@/lib/case';
import { toUiError, type UiError } from '@/lib/errors';
import {
  formatDate,
  formatDateTime,
  outcomeLabel,
  sourceLabel,
} from '@/lib/format';
import styles from './CaseDetailView.module.css';

function outcomeTone(outcome: string): BadgeTone {
  switch (outcome) {
    case 'RESOLVED':
      return 'positive';
    case 'PARTIALLY_RESOLVED':
      return 'warning';
    case 'UNRESOLVED':
      return 'negative';
    default:
      return 'neutral';
  }
}

function referenceIds(detail: CaseDetail): string[] {
  return detail.source === 'ANALYST'
    ? Array.from(detail.referenceCaseIds)
    : detail.referenceCaseIds;
}

export function CaseDetailView({ id }: { id: string }) {
  const { cacheReference, referenceCache } = useAppState();
  const [detail, setDetail] = useState<CaseDetail | null>(null);
  const [references, setReferences] = useState<Record<string, CaseSummary>>({});
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<UiError | null>(null);
  const requestId = useRef(0);

  async function load() {
    const current = ++requestId.current;
    setLoading(true);
    setError(null);
    try {
      const result = await getApiClient().cases.getCase({ id });
      if (requestId.current !== current) return;
      setDetail(result);
      setReferences((existing) => {
        const next = { ...existing };
        for (const referenceId of referenceIds(result)) {
          const cached = referenceCache[referenceId];
          if (cached) next[referenceId] = cached;
        }
        return next;
      });
    } catch (caught) {
      if (requestId.current === current) {
        setDetail(null);
        setError(toUiError(caught));
      }
    } finally {
      if (requestId.current === current) setLoading(false);
    }
  }

  useEffect(() => {
    void load();
    // Reload when the route or the global data revision changes.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [id]);

  const ids = detail ? referenceIds(detail) : [];
  const idsKey = ids.join('|');

  useEffect(() => {
    if (!detail || ids.length === 0) return;
    let cancelled = false;
    const missing = ids.filter((referenceId) => !references[referenceId]);
    if (missing.length === 0) return;

    void Promise.allSettled(
      missing.map(async (referenceId) => {
        const reference = await getApiClient().cases.getCase({ id: referenceId });
        return { referenceId, reference };
      }),
    ).then((results) => {
      if (cancelled) return;
      const additions: Record<string, CaseSummary> = {};
      for (const result of results) {
        if (result.status !== 'fulfilled') continue;
        const summary: CaseSummary = {
          id: result.value.reference.id,
          source: result.value.reference.source,
          product: result.value.reference.product,
          subProduct: result.value.reference.subProduct,
          issue: result.value.reference.issue,
          subIssue: result.value.reference.subIssue,
          receivedDate: result.value.reference.receivedDate,
          company: result.value.reference.company,
          companyResponse: result.value.reference.companyResponse,
          createdAt: result.value.reference.createdAt,
          updatedAt: result.value.reference.updatedAt,
        };
        additions[summary.id] = summary;
        cacheReference(summary);
      }
      if (Object.keys(additions).length) {
        setReferences((existing) => ({ ...existing, ...additions }));
      }
    });

    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [idsKey, detail]);

  if (loading) {
    return (
      <div className={`fin-container ${styles.page}`}>
        <LoadingState label="Loading case detail…" />
      </div>
    );
  }

  if (error || !detail) {
    return (
      <div className={`fin-container ${styles.page}`}>
        <StatusMessage tone="error" title={error?.title ?? 'Case unavailable'}>
          <p>{error?.message ?? 'The case could not be loaded.'}</p>
          <div className={styles.inlineActions}>
            <Button variant="tertiary" onClick={() => void load()}>Try again</Button>
            <LinkButton href="/cases" variant="tertiary">Back to case library</LinkButton>
          </div>
        </StatusMessage>
      </div>
    );
  }

  return (
    <div className={`fin-container ${styles.page}`}>
      <div className={styles.topline}>
        <Link href="/cases" className={styles.back}>← Case library</Link>
        {detail.source === 'ANALYST' ? (
          <LinkButton href={`/cases/${detail.id}/edit`} variant="tertiary">
            Edit case
          </LinkButton>
        ) : (
          <Badge tone="neutral">Read-only archive case</Badge>
        )}
      </div>

      <header className={styles.header}>
        <div className={styles.badges}>
          <Badge tone={detail.source === 'CFPB' ? 'neutral' : 'ink'}>
            {sourceLabel(detail.source)}
          </Badge>
          {detail.receivedDate ? (
            <span className={styles.meta}>Received {formatDate(detail.receivedDate)}</span>
          ) : null}
        </div>
        <h1 className={styles.title}>{detail.product}</h1>
        <p className={styles.issue}>{detail.issue}</p>
        <p className={styles.path}>
          {categoryPath({
            product: detail.product,
            subProduct: detail.subProduct,
            issue: detail.issue,
            subIssue: detail.subIssue,
          })}
        </p>
      </header>

      <div className={styles.layout}>
        <div className={styles.mainColumn}>
          <Card as="section" className={styles.section}>
            <h2 className={styles.sectionTitle}>Complaint narrative</h2>
            <p className={styles.narrative}>{detail.narrative}</p>
          </Card>

          <Card as="section" className={styles.section}>
            <h2 className={styles.sectionTitle}>
              {detail.source === 'CFPB'
                ? 'Company response (CFPB archive)'
                : 'Company response (analyst-reported)'}
            </h2>
            <dl className={styles.details}>
              <div>
                <dt>Company</dt>
                <dd>{detail.company ?? 'Not recorded'}</dd>
              </div>
              <div>
                <dt>Response</dt>
                <dd>{detail.companyResponse ?? 'No company response recorded.'}</dd>
              </div>
            </dl>
            <p className={styles.note}>
              A historical response is context, not a verified recipe. Similar cases do
              not always call for the same outcome.
            </p>
          </Card>

          {detail.source === 'ANALYST' ? (
            <Card as="section" className={styles.section}>
              <div className={styles.sectionHeading}>
                <h2 className={styles.sectionTitle}>Analyst notes</h2>
                <Badge tone={outcomeTone(detail.analystNotes.outcomeAssessment)}>
                  {outcomeLabel(detail.analystNotes.outcomeAssessment)}
                </Badge>
              </div>
              <dl className={styles.details}>
                <div>
                  <dt>Handling notes</dt>
                  <dd className={styles.preWrap}>{detail.analystNotes.handlingNotes}</dd>
                </div>
                <div>
                  <dt>Evaluation notes</dt>
                  <dd className={styles.preWrap}>
                    {detail.analystNotes.evaluationNotes || 'No evaluation notes recorded.'}
                  </dd>
                </div>
              </dl>
              <p className={styles.note}>
                Outcome assessment is the analyst's judgement, not a system verification.
              </p>
            </Card>
          ) : null}
        </div>

        <aside className={styles.sideColumn} aria-label="Case metadata">
          <Card as="section" className={styles.section}>
            <h2 className={styles.sectionTitle}>Category</h2>
            <dl className={styles.details}>
              <div><dt>Product</dt><dd>{detail.product}</dd></div>
              <div><dt>Sub-product</dt><dd>{detail.subProduct ?? 'Not specified'}</dd></div>
              <div><dt>Issue</dt><dd>{detail.issue}</dd></div>
              <div><dt>Sub-issue</dt><dd>{detail.subIssue ?? 'Not specified'}</dd></div>
            </dl>
          </Card>

          <Card as="section" className={styles.section}>
            <h2 className={styles.sectionTitle}>
              {detail.source === 'CFPB' ? 'CFPB provenance' : 'Case record'}
            </h2>
            {detail.source === 'CFPB' ? (
              <dl className={styles.details}>
                <div><dt>Complaint ID</dt><dd>{detail.provenance.complaintId}</dd></div>
                <div><dt>Import batch</dt><dd className={styles.mono}>{detail.provenance.importBatchId}</dd></div>
                <div>
                  <dt>Archive URL</dt>
                  <dd>
                    <a
                      href={detail.provenance.archiveUrl}
                      target="_blank"
                      rel="noreferrer noopener"
                    >
                      {detail.provenance.archiveUrl}
                    </a>
                  </dd>
                </div>
                <div><dt>Source file</dt><dd>{detail.provenance.sourceFileName}</dd></div>
                <div>
                  <dt>Period</dt>
                  <dd>
                    {formatDate(detail.provenance.periodStart)} –{' '}
                    {formatDate(detail.provenance.periodEnd)}
                  </dd>
                </div>
                <div><dt>CSV SHA-256</dt><dd className={styles.mono}>{detail.provenance.csvSha256}</dd></div>
              </dl>
            ) : (
              <dl className={styles.details}>
                <div><dt>Case ID</dt><dd className={styles.mono}>{detail.id}</dd></div>
                <div><dt>Created</dt><dd>{formatDateTime(detail.createdAt)}</dd></div>
                <div><dt>Updated</dt><dd>{formatDateTime(detail.updatedAt)}</dd></div>
                <div>
                  <dt>Provenance</dt>
                  <dd>Retained directly by an analyst. No CFPB archive provenance.</dd>
                </div>
              </dl>
            )}
          </Card>

          <Card as="section" className={styles.section}>
            <h2 className={styles.sectionTitle}>References</h2>
            {ids.length === 0 ? (
              <p className={styles.note}>No reference cases are recorded.</p>
            ) : (
              <ul className={styles.referenceList}>
                {ids.map((referenceId) => {
                  const reference = references[referenceId];
                  return (
                    <li key={referenceId}>
                      <Link href={`/cases/${referenceId}`} className={styles.referenceLink}>
                        {reference ? (
                          <>
                            <span>{reference.product}</span>
                            <span className={styles.referenceIssue}>{reference.issue}</span>
                          </>
                        ) : (
                          <span className={styles.mono}>{referenceId}</span>
                        )}
                      </Link>
                    </li>
                  );
                })}
              </ul>
            )}
          </Card>
        </aside>
      </div>
    </div>
  );
}
