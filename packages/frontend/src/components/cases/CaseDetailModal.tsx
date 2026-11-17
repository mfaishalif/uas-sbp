'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import type { CaseDetail } from '@finresolve/api-client';
import { Badge } from '@/components/ui/Badge';
import { Button } from '@/components/ui/Button';
import { LoadingState } from '@/components/ui/LoadingState';
import { Modal } from '@/components/ui/Modal';
import { StatusMessage } from '@/components/ui/StatusMessage';
import { getApiClient } from '@/lib/api';
import { toUiError, type UiError } from '@/lib/errors';
import { formatDate, formatDateTime, sourceLabel } from '@/lib/format';
import styles from './CaseDetailModal.module.css';

function referenceIds(detail: CaseDetail): string[] {
  return Array.from(detail.referenceCaseIds);
}

/**
 * Case detail in a dialog. Opening a reference replaces the content in the same
 * dialog and pushes onto a trail, so "Back" returns to the previous case and
 * closing still returns focus to whatever opened the dialog.
 */
export function CaseDetailModal({
  caseId,
  onClose,
}: {
  caseId: string | null;
  onClose: () => void;
}) {
  const [trail, setTrail] = useState<string[]>([]);
  const [details, setDetails] = useState<Record<string, CaseDetail>>({});
  const [errors, setErrors] = useState<Record<string, UiError>>({});
  const bodyRef = useRef<HTMLDivElement>(null);
  const moved = useRef(false);
  const pending = useRef(new Set<string>());

  useEffect(() => {
    setTrail(caseId ? [caseId] : []);
    moved.current = false;
  }, [caseId]);

  const currentId = trail[trail.length - 1] ?? null;
  const detail = currentId ? details[currentId] : undefined;
  const error = currentId ? errors[currentId] : undefined;

  const fetchCase = useCallback(async (id: string) => {
    if (pending.current.has(id)) return;
    pending.current.add(id);
    setErrors((current) => {
      if (!(id in current)) return current;
      const next = { ...current };
      delete next[id];
      return next;
    });
    try {
      const result = await getApiClient().cases.getCase({ id });
      setDetails((current) => ({ ...current, [id]: result }));
    } catch (caught) {
      setErrors((current) => ({ ...current, [id]: toUiError(caught) }));
    } finally {
      pending.current.delete(id);
    }
  }, []);

  useEffect(() => {
    if (currentId && !details[currentId] && !errors[currentId]) void fetchCase(currentId);
  }, [currentId, details, errors, fetchCase]);

  // Load the referenced cases so the list can show what each one is.
  const refsKey = detail ? referenceIds(detail).join('|') : '';
  useEffect(() => {
    if (!detail) return;
    for (const id of referenceIds(detail)) {
      if (!details[id] && !errors[id]) void fetchCase(id);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [refsKey]);

  // After moving between cases inside the dialog, put focus at the top of the new content.
  useEffect(() => {
    if (moved.current && detail) bodyRef.current?.focus();
  }, [currentId, detail]);

  function openReference(id: string) {
    moved.current = true;
    setTrail((current) => [...current, id]);
  }

  function goBack() {
    moved.current = true;
    setTrail((current) => (current.length > 1 ? current.slice(0, -1) : current));
  }

  const title = detail ? detail.product : 'Case detail';

  return (
    <Modal
      open={caseId !== null}
      title={title}
      description={detail ? detail.issue : undefined}
      onClose={onClose}
    >
      <div className={styles.body} ref={bodyRef} tabIndex={-1}>
        {trail.length > 1 ? (
          <Button variant="quiet" onClick={goBack} className={styles.back}>
            ← Back to previous case
          </Button>
        ) : null}

        {error ? (
          <StatusMessage tone="error" title={error.title}>
            <p>{error.message}</p>
            <Button variant="tertiary" onClick={() => currentId && void fetchCase(currentId)}>
              Try again
            </Button>
          </StatusMessage>
        ) : null}

        {!detail && !error ? <LoadingState label="Loading case…" compact /> : null}

        {detail ? <CaseDetailBody detail={detail} details={details} onOpenReference={openReference} /> : null}
      </div>
    </Modal>
  );
}

function CaseDetailBody({
  detail,
  details,
  onOpenReference,
}: {
  detail: CaseDetail;
  details: Record<string, CaseDetail>;
  onOpenReference: (id: string) => void;
}) {
  const references = referenceIds(detail);

  return (
    <>
      <div className={styles.meta}>
        <Badge tone={detail.source === 'CFPB' ? 'neutral' : 'ink'}>{sourceLabel(detail.source)}</Badge>
        <span>Received {formatDate(detail.receivedDate)}</span>
        {detail.source === 'ANALYST' ? (
          <span>Saved {formatDateTime(detail.createdAt)}</span>
        ) : (
          <span>CFPB complaint {detail.provenance.complaintId}</span>
        )}
      </div>

      <section className={styles.section} aria-labelledby="case-category">
        <h3 className={styles.sectionTitle} id="case-category">Category</h3>
        <dl className={styles.details}>
          <div><dt>Product</dt><dd>{detail.product}</dd></div>
          <div><dt>Sub-product</dt><dd>{detail.subProduct ?? 'Not specified'}</dd></div>
          <div><dt>Issue</dt><dd>{detail.issue}</dd></div>
          <div><dt>Sub-issue</dt><dd>{detail.subIssue ?? 'Not specified'}</dd></div>
        </dl>
      </section>

      <section className={styles.section} aria-labelledby="case-narrative">
        <h3 className={styles.sectionTitle} id="case-narrative">Complaint narrative</h3>
        <p className={styles.narrative}>{detail.narrative}</p>
      </section>

      <section className={styles.section} aria-labelledby="case-response">
        <h3 className={styles.sectionTitle} id="case-response">Company response</h3>
        <dl className={styles.details}>
          <div><dt>Company</dt><dd>{detail.company ?? 'Not recorded'}</dd></div>
          <div><dt>Response</dt><dd>{detail.companyResponse ?? 'Not recorded'}</dd></div>
        </dl>
      </section>

      {detail.source === 'ANALYST' ? (
        <>
          <section className={styles.section} aria-labelledby="case-notes">
            <h3 className={styles.sectionTitle} id="case-notes">Handling notes</h3>
            <p className={styles.narrative}>{detail.analystNotes.handlingNotes}</p>
          </section>

          <section className={styles.section} aria-labelledby="case-references">
            <h3 className={styles.sectionTitle} id="case-references">References</h3>
            {references.length === 0 ? (
              <p className={styles.muted}>No reference cases are recorded.</p>
            ) : (
              <ul className={styles.references}>
                {references.map((id) => {
                  const reference = details[id];
                  return (
                    <li key={id}>
                      <button
                        type="button"
                        className={styles.reference}
                        onClick={() => onOpenReference(id)}
                      >
                        {reference ? (
                          <>
                            <span className={styles.referenceSource}>{sourceLabel(reference.source)}</span>
                            <span className={styles.referenceText}>
                              <span>{reference.product}</span>
                              <span className={styles.muted}>{reference.issue}</span>
                            </span>
                          </>
                        ) : (
                          <span className={styles.mono}>{id}</span>
                        )}
                        <span aria-hidden="true">→</span>
                      </button>
                    </li>
                  );
                })}
              </ul>
            )}
          </section>
        </>
      ) : null}
    </>
  );
}
