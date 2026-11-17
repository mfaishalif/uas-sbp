'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import type { CasePage, CaseSummary, CaseSummarySourceEnum } from '@finresolve/api-client';
import { CaseDetailModal } from '@/components/cases/CaseDetailModal';
import { FocusFormLink } from '@/components/home/FocusFormLink';
import { Badge } from '@/components/ui/Badge';
import { Button } from '@/components/ui/Button';
import { Card } from '@/components/ui/Card';
import { EmptyState } from '@/components/ui/EmptyState';
import { LoadingState } from '@/components/ui/LoadingState';
import { TextInput } from '@/components/ui/FormField';
import { StatusMessage } from '@/components/ui/StatusMessage';
import { getApiClient } from '@/lib/api';
import { toUiError, type UiError } from '@/lib/errors';
import { formatDate, sourceLabel } from '@/lib/format';
import styles from './CaseLibraryPage.module.css';

const PAGE_SIZE = 20;
type SourceFilter = '' | CaseSummarySourceEnum;

const SOURCES: { value: SourceFilter; label: string }[] = [
  { value: '', label: 'All' },
  { value: 'CFPB', label: 'CFPB' },
  { value: 'ANALYST', label: 'Analyst' },
];

/** Case-insensitive match over the fields a summary carries (no narrative in list results). */
export function matchesCaseQuery(item: CaseSummary, query: string): boolean {
  const needle = query.trim().toLocaleLowerCase('en-US');
  if (!needle) return true;
  return [
    item.product,
    item.subProduct,
    item.issue,
    item.subIssue,
    item.company,
    item.companyResponse,
    sourceLabel(item.source),
  ].some((value) => value?.toLocaleLowerCase('en-US').includes(needle));
}

function CaseCard({ item, onOpen }: { item: CaseSummary; onOpen: () => void }) {
  const titleId = `case-${item.id}-title`;
  return (
    <Card as="article" className={styles.card} aria-labelledby={titleId}>
      <div className={styles.cardHead}>
        <Badge tone={item.source === 'CFPB' ? 'neutral' : 'ink'}>{sourceLabel(item.source)}</Badge>
        <span className={styles.date}>
          {item.source === 'ANALYST'
            ? `Saved ${formatDate(item.createdAt.slice(0, 10))}`
            : `Received ${formatDate(item.receivedDate)}`}
        </span>
      </div>
      <h2 className={styles.cardTitle} id={titleId}>
        <button type="button" className={styles.cardButton} onClick={onOpen}>
          {item.product}
          <span className={styles.issue}> · {item.issue}</span>
        </button>
      </h2>
      {item.subProduct || item.subIssue ? (
        <p className={styles.path}>{[item.subProduct, item.subIssue].filter(Boolean).join(' / ')}</p>
      ) : null}
      <dl className={styles.meta}>
        <div>
          <dt>Company</dt>
          <dd>{item.company ?? 'Not recorded'}</dd>
        </div>
        <div>
          <dt>Response</dt>
          <dd>{item.companyResponse ?? 'Not recorded'}</dd>
        </div>
      </dl>
      <span className={styles.open} aria-hidden="true">View details →</span>
    </Card>
  );
}

export function CaseLibraryPage() {
  const [page, setPage] = useState(0);
  const [source, setSource] = useState<SourceFilter>('');
  const [query, setQuery] = useState('');
  const [result, setResult] = useState<CasePage | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<UiError | null>(null);
  const [openCaseId, setOpenCaseId] = useState<string | null>(null);
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
  }, [load, page, source]);

  function changeSource(value: SourceFilter) {
    setSource(value);
    setPage(0);
    setResult(null);
  }

  const items = result?.items ?? [];
  const visible = items.filter((item) => matchesCaseQuery(item, query));
  const currentPage = result?.page ?? page;
  const totalPages = result?.totalPages ?? 0;
  const isFiltered = source !== '';
  const libraryEmpty = !loading && !error && result !== null && items.length === 0;
  const filteredEmpty = !loading && !error && items.length > 0 && visible.length === 0;
  const sourceName = SOURCES.find((option) => option.value === source)?.label ?? 'All';

  return (
    <div className={`fin-container fin-page ${styles.page}`}>
      <header className={styles.header}>
        <div className={styles.heading}>
          <p className="page-eyebrow">Knowledge base</p>
          <h1 className={styles.title}>Case library</h1>
          <p className={styles.lead}>
            Every case search can return: the CFPB archive and the cases analysts have
            saved. Open a case to read its narrative, response, and references.
          </p>
        </div>
        <FocusFormLink>Find precedents</FocusFormLink>
      </header>

      <div className={styles.toolbar}>
        <fieldset className={styles.sources}>
          <legend className={styles.legend}>Source</legend>
          <div className={styles.segmented}>
            {SOURCES.map((option) => (
              <label key={option.value || 'all'} className={styles.segment}>
                <input
                  type="radio"
                  name="case-source"
                  value={option.value}
                  checked={source === option.value}
                  onChange={() => changeSource(option.value)}
                />
                <span>{option.label}</span>
              </label>
            ))}
          </div>
        </fieldset>

        <TextInput
          id="case-filter"
          type="search"
          label="Filter this page"
          value={query}
          onChange={(event) => setQuery(event.target.value)}
          hint="Matches product, issue, company, and response among the cases on this page."
          containerClassName={styles.filter}
          autoComplete="off"
        />
      </div>

      <p className={styles.count} role="status" aria-live="polite">
        {result
          ? query.trim()
            ? `${visible.length} of ${items.length} cases on this page match “${query.trim()}”.`
            : `${result.totalElements.toLocaleString('en-US')} ${result.totalElements === 1 ? 'case' : 'cases'}${isFiltered ? ` from ${sourceName}` : ''} · ${PAGE_SIZE} per page`
          : 'Loading cases…'}
      </p>

      {loading ? <LoadingState label="Loading cases…" /> : null}

      {error ? (
        <StatusMessage tone="error" title={error.title}>
          <p>{error.message}</p>
          <Button variant="tertiary" onClick={() => void load(page, source)}>
            Try again
          </Button>
        </StatusMessage>
      ) : null}

      {libraryEmpty ? (
        <Card className={styles.emptyCard}>
          <EmptyState
            title={isFiltered ? `No ${sourceName} cases yet` : 'The case library is empty'}
            action={
              isFiltered ? (
                <Button variant="tertiary" onClick={() => changeSource('')}>
                  Show all sources
                </Button>
              ) : (
                <FocusFormLink>Find precedents</FocusFormLink>
              )
            }
          >
            {isFiltered ? (
              <p>
                {source === 'ANALYST'
                  ? 'Analyst cases appear here after you save one from the precedent search.'
                  : 'Try another source or return to all cases.'}
              </p>
            ) : (
              <p>
                The CFPB sample loads when the API starts with demo data enabled. Cases
                you save from the precedent search are added here too.
              </p>
            )}
          </EmptyState>
        </Card>
      ) : null}

      {filteredEmpty ? (
        <Card className={styles.emptyCard}>
          <EmptyState
            title="No cases on this page match"
            action={
              <Button variant="tertiary" onClick={() => setQuery('')}>
                Clear filter
              </Button>
            }
          >
            <p>The filter only checks the {items.length} cases on this page. Try another page or term.</p>
          </EmptyState>
        </Card>
      ) : null}

      {!loading && !error && visible.length > 0 ? (
        <div className={styles.list}>
          {visible.map((item) => (
            <CaseCard key={item.id} item={item} onOpen={() => setOpenCaseId(item.id)} />
          ))}
        </div>
      ) : null}

      {result && items.length > 0 ? (
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

      <CaseDetailModal caseId={openCaseId} onClose={() => setOpenCaseId(null)} />
    </div>
  );
}
