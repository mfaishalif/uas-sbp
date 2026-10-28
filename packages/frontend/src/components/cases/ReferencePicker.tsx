'use client';

import { useEffect, useRef, useState } from 'react';
import type { CasePage, CaseSummary, CaseSummarySourceEnum } from '@finresolve/api-client';
import { Badge } from '@/components/ui/Badge';
import { Button } from '@/components/ui/Button';
import { EmptyState } from '@/components/ui/EmptyState';
import { Select } from '@/components/ui/FormField';
import { LoadingState } from '@/components/ui/LoadingState';
import { Modal } from '@/components/ui/Modal';
import { StatusMessage } from '@/components/ui/StatusMessage';
import { useAppState } from '@/contexts/AppState';
import { getApiClient } from '@/lib/api';
import { hasReference } from '@/lib/case';
import { toUiError, type UiError } from '@/lib/errors';
import { formatDate, sourceLabel } from '@/lib/format';
import styles from './ReferencePicker.module.css';

const PICKER_PAGE_SIZE = 10;
type SourceFilter = '' | CaseSummarySourceEnum;

export function ReferencePicker({
  open,
  onClose,
  selectedIds,
  excludeId,
  maxReached,
  onAdd,
}: {
  open: boolean;
  onClose: () => void;
  selectedIds: Set<string>;
  excludeId?: string;
  maxReached: boolean;
  onAdd: (summary: CaseSummary) => void;
}) {
  const { dataVersion } = useAppState();
  const [page, setPage] = useState(0);
  const [source, setSource] = useState<SourceFilter>('');
  const [result, setResult] = useState<CasePage | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<UiError | null>(null);
  const [reloadKey, setReloadKey] = useState(0);
  const requestId = useRef(0);

  useEffect(() => {
    if (open) setPage(0);
  }, [open]);

  useEffect(() => {
    if (!open) return;
    const current = ++requestId.current;
    setLoading(true);
    setError(null);
    void getApiClient()
      .cases.listCases({
        page,
        size: PICKER_PAGE_SIZE,
        source: source || undefined,
      })
      .then((response) => {
        if (requestId.current === current) setResult(response);
      })
      .catch((caught) => {
        if (requestId.current === current) setError(toUiError(caught));
      })
      .finally(() => {
        if (requestId.current === current) setLoading(false);
      });
  }, [open, page, source, dataVersion, reloadKey]);

  const currentPage = result?.page ?? page;
  const totalPages = result?.totalPages ?? 0;

  return (
    <Modal
      open={open}
      title="Add reference from the case library"
      description="Choose up to 20 precedent cases. Reference IDs are saved with the analyst case."
      onClose={onClose}
    >
      <div className={styles.picker}>
        <div className={styles.toolbar}>
          <Select
            id="reference-source"
            label="Source"
            value={source}
            onChange={(event) => {
              setSource(event.target.value as SourceFilter);
              setPage(0);
            }}
            containerClassName={styles.sourceSelect}
          >
            <option value="">All sources</option>
            <option value="CFPB">CFPB archive</option>
            <option value="ANALYST">Analyst cases</option>
          </Select>
          <p className={styles.count}>
            {result ? `${result.totalElements.toLocaleString('en-US')} available` : ''}
          </p>
        </div>

        {maxReached ? (
          <StatusMessage tone="info">
            <p>You already selected the maximum of 20 references.</p>
          </StatusMessage>
        ) : null}

        {loading ? <LoadingState label="Loading cases…" compact /> : null}

        {error ? (
          <StatusMessage tone="error" title={error.title}>
            <p>{error.message}</p>
            <Button variant="tertiary" onClick={() => setReloadKey((value) => value + 1)}>
              Try again
            </Button>
          </StatusMessage>
        ) : null}

        {!loading && !error && result && result.items.length === 0 ? (
          <EmptyState title="No cases in this source">
            <p>Try another source or add the first analyst case.</p>
          </EmptyState>
        ) : null}

        {!loading && !error && result ? (
          <ul className={styles.list}>
            {result.items.map((item) => {
              const already = hasReference(selectedIds, item.id);
              const self = Boolean(excludeId && item.id.toLowerCase() === excludeId.toLowerCase());
              return (
                <li key={item.id} className={styles.item}>
                  <div className={styles.itemMain}>
                    <div className={styles.itemBadges}>
                      <Badge tone={item.source === 'CFPB' ? 'neutral' : 'ink'}>
                        {sourceLabel(item.source)}
                      </Badge>
                      <span className={styles.date}>{formatDate(item.receivedDate)}</span>
                    </div>
                    <p className={styles.itemTitle}>{item.product}</p>
                    <p className={styles.itemIssue}>{item.issue}</p>
                    {item.company ? (
                      <p className={styles.company}>{item.company}</p>
                    ) : null}
                  </div>
                  <Button
                    variant={already ? 'secondary' : 'tertiary'}
                    disabled={already || self || maxReached}
                    onClick={() => onAdd(item)}
                  >
                    {self ? 'This case' : already ? 'Added' : 'Add reference'}
                  </Button>
                </li>
              );
            })}
          </ul>
        ) : null}

        {result && result.items.length > 0 ? (
          <div className={styles.pagination}>
            <Button
              variant="tertiary"
              disabled={currentPage <= 0 || loading}
              onClick={() => setPage(Math.max(0, currentPage - 1))}
            >
              Previous
            </Button>
            <span>
              Page {currentPage + 1} of {Math.max(1, totalPages)}
            </span>
            <Button
              variant="tertiary"
              disabled={!result.hasNext || loading}
              onClick={() => setPage(currentPage + 1)}
            >
              Next
            </Button>
          </div>
        ) : null}
      </div>
    </Modal>
  );
}
