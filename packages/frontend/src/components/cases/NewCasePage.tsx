'use client';

import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { CaseForm } from '@/components/cases/CaseForm';
import { Card } from '@/components/ui/Card';
import { useAppState } from '@/contexts/AppState';
import { getApiClient } from '@/lib/api';
import { buildRetainCaseRequest } from '@/lib/case';
import styles from './CaseFormPage.module.css';

export function NewCasePage() {
  const router = useRouter();
  const {
    retainDraft,
    updateRetainDraft,
    resetRetainDraft,
    clearSearchSelection,
    notifyDataChanged,
  } = useAppState();

  return (
    <div className={`fin-container ${styles.page}`}>
      <div className={styles.topline}>
        <Link href="/cases" className={styles.back}>← Case library</Link>
      </div>
      <header className={styles.header}>
        <p className="page-eyebrow">Retain knowledge</p>
        <h1 className={styles.title}>Add analyst case</h1>
        <p className={styles.lead}>
          Keep the complaint details, handling notes, outcome assessment, and any
          precedent references together. The retained case joins the searchable
          knowledge base immediately.
        </p>
      </header>

      <Card className={styles.notice}>
        <p>
          A retained case is analyst-authored context, not proof that the handling
          approach succeeded. Narratives and category labels are stored as entered.
        </p>
      </Card>

      <CaseForm
        mode="create"
        values={retainDraft}
        onChange={updateRetainDraft}
        currentCaseId={undefined}
        cancelHref="/cases"
        onSubmit={async (values) => (
          getApiClient().cases.retainCase({
            retainCaseRequest: buildRetainCaseRequest(values),
          })
        )}
        onSuccess={(detail) => {
          resetRetainDraft();
          clearSearchSelection();
          notifyDataChanged();
          router.push(`/cases/${detail.id}`);
        }}
      />
    </div>
  );
}
