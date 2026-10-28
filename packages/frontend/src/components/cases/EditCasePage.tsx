'use client';

import Link from 'next/link';
import { useEffect, useRef, useState } from 'react';
import { useRouter } from 'next/navigation';
import type { CaseDetail } from '@finresolve/api-client';
import { CaseForm } from '@/components/cases/CaseForm';
import { Button } from '@/components/ui/Button';
import { Card } from '@/components/ui/Card';
import { LinkButton } from '@/components/ui/LinkButton';
import { LoadingState } from '@/components/ui/LoadingState';
import { StatusMessage } from '@/components/ui/StatusMessage';
import { useAppState } from '@/contexts/AppState';
import { getApiClient } from '@/lib/api';
import {
  buildRetainCaseRequest,
  caseDetailToForm,
  type CaseFormValues,
  type DraftUpdater,
} from '@/lib/case';
import { toUiError, type UiError } from '@/lib/errors';
import styles from './CaseFormPage.module.css';

export function EditCasePage({ id }: { id: string }) {
  const router = useRouter();
  const {
    editDrafts,
    updateEditDraft,
    clearEditDraft,
    referenceCache,
    cacheReference,
    notifyDataChanged,
  } = useAppState();
  const [detail, setDetail] = useState<CaseDetail | null>(null);
  const [values, setValues] = useState<CaseFormValues | null>(null);
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
      if (result.source === 'ANALYST') {
        const initial = editDrafts[id] ?? caseDetailToForm(result);
        setValues(initial);
        updateEditDraft(id, initial);
      }
    } catch (caught) {
      if (requestId.current === current) {
        setDetail(null);
        setValues(null);
        setError(toUiError(caught));
      }
    } finally {
      if (requestId.current === current) setLoading(false);
    }
  }

  useEffect(() => {
    void load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [id]);

  const referenceIds = values ? Array.from(values.referenceCaseIds) : [];
  const referenceKey = referenceIds.join('|');

  useEffect(() => {
    if (!values || referenceIds.length === 0) return;
    let cancelled = false;
    const missing = referenceIds.filter((referenceId) => !referenceCache[referenceId]);
    if (!missing.length) return;
    void Promise.allSettled(
      missing.map((referenceId) => getApiClient().cases.getCase({ id: referenceId })),
    ).then((results) => {
      if (cancelled) return;
      for (const result of results) {
        if (result.status === 'fulfilled') cacheReference(result.value);
      }
    });
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [referenceKey, values]);

  function changeValues(update: DraftUpdater<CaseFormValues>) {
    setValues((current) => {
      if (!current) return current;
      const next = typeof update === 'function' ? update(current) : { ...current, ...update };
      updateEditDraft(id, next);
      return next;
    });
  }

  if (loading) {
    return (
      <div className={`fin-container ${styles.page}`}>
        <LoadingState label="Loading the complete case before editing…" />
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

  if (detail.source === 'CFPB') {
    return (
      <div className={`fin-container ${styles.page}`}>
        <div className={styles.topline}>
          <Link href={`/cases/${detail.id}`} className={styles.back}>
            ← Back to case
          </Link>
        </div>
        <header className={styles.header}>
          <p className="page-eyebrow">Read-only archive</p>
          <h1 className={styles.title}>This CFPB case cannot be edited</h1>
          <p className={styles.lead}>
            Cases imported from the CFPB archive are preserved as source records.
            Only analyst-retained cases can be edited.
          </p>
        </header>
        <Card className={styles.notice}>
          <p>
            The archive narrative, category, response, and provenance are immutable in
            this demo.
          </p>
          <LinkButton href={`/cases/${detail.id}`} variant="primary">
            View the case
          </LinkButton>
        </Card>
      </div>
    );
  }

  if (!values) {
    return (
      <div className={`fin-container ${styles.page}`}>
        <StatusMessage tone="error" title="Case detail could not be prepared">
          <p>The case was loaded without editable analyst details.</p>
        </StatusMessage>
      </div>
    );
  }

  return (
    <div className={`fin-container ${styles.page}`}>
      <div className={styles.topline}>
        <Link href={`/cases/${detail.id}`} className={styles.back}>← Back to case</Link>
      </div>
      <header className={styles.header}>
        <p className="page-eyebrow">Edit analyst case</p>
        <h1 className={styles.title}>Edit analyst case</h1>
        <p className={styles.lead}>
          The complete case was loaded before this form appeared. Saving replaces the
          full analyst case, including optional fields and references.
        </p>
      </header>

      <Card className={styles.notice}>
        <p>
          This is a full replacement, not a partial update. Clearing an optional field
          removes its stored value. If saving fails, every field in this form stays in
          place for another attempt.
        </p>
      </Card>

      <CaseForm
        mode="edit"
        values={values}
        onChange={changeValues}
        currentCaseId={detail.id}
        cancelHref={`/cases/${detail.id}`}
        onSubmit={async (nextValues) => (
          getApiClient().cases.replaceAnalystCase({
            id: detail.id,
            retainCaseRequest: buildRetainCaseRequest(nextValues),
          })
        )}
        onSuccess={(updated) => {
          clearEditDraft(detail.id);
          notifyDataChanged();
          router.push(`/cases/${updated.id}`);
        }}
      />
    </div>
  );
}
