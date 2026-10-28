'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import type { CasePage, CaseSummarySourceEnum } from '@finresolve/api-client';
import { Button } from '@/components/ui/Button';
import { Card } from '@/components/ui/Card';
import { EmptyState } from '@/components/ui/EmptyState';
import { LinkButton } from '@/components/ui/LinkButton';
import { LoadingState } from '@/components/ui/LoadingState';
import { Select } from '@/components/ui/FormField';
import { StatusMessage } from '@/components/ui/StatusMessage';
import { CaseSummaryCard } from '@/components/cases/CaseSummaryCard';
import { useAppState } from '@/contexts/AppState';
import { getApiClient } from '@/lib/api';
import { toUiError, type UiError } from '@/lib/errors';
import styles from './CaseLibraryPage.module.css';

const PAGE_SIZE = 20;
type SourceFilter = '' | CaseSummarySourceEnum;

export function CaseLibraryPage() {
  const { dataVersion } = useAppState();
  const [page, setPage] = useState(0);
  const [source, setSource] = useState<SourceFilter>('');
  const [result, setResult] = useState<CasePage | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<UiError | null>(null);
  const requestId = useRef(0);

  const load = useCallback(async (nextPage: number, nextSource: SourceFilter) => {
    const current = ++requestId.current;
    setLoading(true);
    setError(null);
    try {
      const response = await getApiClient().cases.listCases({
        page: nextPage,
        size: PAGE_SIZE,
        source: nextSource || undefined,
      });
      if (requestId.current === current) setResult(response);
    } catch (caught) {
      if (requestId.current === current) setError(toUiError(caught));
    } finally {
      if (requestId.current === current) setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load(page, source);
  }, [load, page, source, dataVersion]);

  function changeSource(value: SourceFilter) {
    setSource(value);
    setPage(0);
    setResult(null);
  }

  const currentPage = result?.page ?? page;
  const totalPages = result?.totalPages ?? 0;
  const isFiltered = source !== '';
  const empty = !loading && !error && result && result.items.length === 0;

  return (
    <div className={`fin-container ${styles.page}`}>
      <header className={styles.header}>
        <div className={styles.heading}>
          <p className="page-eyebrow">Knowledge base</p>
          <h1 className={styles.title}>Case library</h1>
          <p className={styles.lead}>
            Browse CFPB archive cases and analyst-retained cases. Open any case to
            review its narrative, provenance, and historical response.
          </p>
        </div>
        <LinkButton href="/cases/new">Add analyst case</LinkButton>
      </header>

      <div className={styles.toolbar}>
        <Select
          id="case-source"
          label="Source"
          value={source}
          onChange={(event) => changeSource(event.target.value as SourceFilter)}
          containerClassName={styles.filter}
        >
          <option value="">All sources</option>
          <option value="CFPB">CFPB archive</option>
          <option value="ANALYST">Analyst cases</option>
        </Select>
        <p className={styles.count} role="status" aria-live="polite">
          {result
            ? `${result.totalElements.toLocaleString('en-US')} ${result.totalElements === 1 ? 'case' : 'cases'}${isFiltered ? ' in this source' : ''} · 20 per page`
            : 'Loading case count…'}
        </p>
      </div>

      {loading ? <LoadingState label="Loading cases…" /> : null}

      {error ? (
        <StatusMessage tone="error" title={error.title}>
          <p>{error.message}</p>
          <Button variant="tertiary" onClick={() => void load(page, source)}>
            Try again
          </Button>
        </StatusMessage>
      ) : null}

      {empty ? (
        <Card className={styles.emptyCard}>
          <EmptyState
            title={isFiltered ? 'No cases match this source' : 'The case library is empty'}
            action={
              isFiltered ? (
                <Button variant="tertiary" onClick={() => changeSource('')}>
                  Show all sources
                </Button>
              ) : (
                <LinkButton href="/imports">Import CFPB data</LinkButton>
              )
            }
          >
            {isFiltered ? (
              <p>Try another source or return to all cases.</p>
            ) : (
              <p>
                Import the CFPB sample or add an analyst case to start building the
                knowledge base.
              </p>
            )}
          </EmptyState>
        </Card>
      ) : null}

      {!loading && !error && result && result.items.length > 0 ? (
        <div className={styles.list}>
          {result.items.map((item) => (
            <CaseSummaryCard key={item.id} item={item} />
          ))}
        </div>
      ) : null}

      {result && result.items.length > 0 ? (
        <nav className={styles.pagination} aria-label="Case library pages">
          <Button
            variant="tertiary"
            onClick={() => setPage(Math.max(0, currentPage - 1))}
            disabled={currentPage <= 0 || loading}
          >
            Previous
          </Button>
          <p>
            Page {currentPage + 1} of {Math.max(1, totalPages)}
          </p>
          <Button
            variant="tertiary"
            onClick={() => setPage(currentPage + 1)}
            disabled={!result.hasNext || loading}
          >
            Next
          </Button>
        </nav>
      ) : null}
    </div>
  );
}
