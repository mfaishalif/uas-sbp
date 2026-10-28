'use client';

import Link from 'next/link';
import { useEffect, useRef, useState, type FormEvent } from 'react';
import type { CaseDetail, CaseSummary } from '@finresolve/api-client';
import { Badge } from '@/components/ui/Badge';
import { Button } from '@/components/ui/Button';
import { Card } from '@/components/ui/Card';
import { LinkButton } from '@/components/ui/LinkButton';
import { Select, Textarea, TextInput } from '@/components/ui/FormField';
import { StatusMessage } from '@/components/ui/StatusMessage';
import { ReferencePicker } from '@/components/cases/ReferencePicker';
import { useAppState } from '@/contexts/AppState';
import { useCatalog } from '@/contexts/CatalogContext';
import {
  addReference,
  categoryPath,
  hasReference,
  OUTCOME_ASSESSMENTS,
  removeReference,
  validateCaseForm,
  type CaseFormErrors,
  type CaseFormValues,
  type DraftUpdater,
  type OutcomeAssessment,
} from '@/lib/case';
import {
  categoryOptions,
  reconcileCategorySelection,
  updateCategorySelection,
  type CategoryField,
} from '@/lib/categories';
import { errorForField, toUiError, type UiError } from '@/lib/errors';
import { fieldLabel, sourceLabel, textLength } from '@/lib/format';
import styles from './CaseForm.module.css';

export interface CaseFormProps {
  mode: 'create' | 'edit';
  values: CaseFormValues;
  onChange: (update: DraftUpdater<CaseFormValues>) => void;
  onSubmit: (values: CaseFormValues) => Promise<CaseDetail>;
  onSuccess: (detail: CaseDetail) => void;
  currentCaseId?: string;
  cancelHref: string;
}

const FIELD_IDS: Record<string, string> = {
  product: 'case-product',
  issue: 'case-issue',
  narrative: 'case-narrative',
  handlingNotes: 'case-handling-notes',
  evaluationNotes: 'case-evaluation-notes',
  company: 'case-company',
  companyResponse: 'case-company-response',
  receivedDate: 'case-received-date',
  referenceCaseIds: 'case-reference-heading',
};

function sameCategory(
  left: CaseFormValues,
  right: CaseFormValues,
): boolean {
  return left.product === right.product
    && left.subProduct === right.subProduct
    && left.issue === right.issue
    && left.subIssue === right.subIssue;
}

export function CaseForm({
  mode,
  values,
  onChange,
  onSubmit,
  onSuccess,
  currentCaseId,
  cancelHref,
}: CaseFormProps) {
  const { items: categories, loading: catalogLoading } = useCatalog();
  const { referenceCache, cacheReference } = useAppState();
  const [errors, setErrors] = useState<CaseFormErrors>({});
  const [serverError, setServerError] = useState<UiError | null>(null);
  const [saving, setSaving] = useState(false);
  const [pickerOpen, setPickerOpen] = useState(false);
  const inFlight = useRef(false);
  const mounted = useRef(true);

  const options = categoryOptions(categories, values);

  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
    };
  }, []);

  useEffect(() => {
    if (catalogLoading || !categories.length) return;
    const reconciled = reconcileCategorySelection(categories, values);
    if (!sameCategory(reconciled, values)) {
      onChange(reconciled);
    }
  }, [categories, catalogLoading, values, onChange]);

  function setField<K extends keyof CaseFormValues>(field: K, value: CaseFormValues[K]) {
    onChange((current) => ({ ...current, [field]: value }));
    setErrors((current) => ({ ...current, [field]: undefined }));
  }

  function changeCategory(field: CategoryField, value: string) {
    onChange((current) => updateCategorySelection(categories, current, field, value));
    setErrors((current) => ({ ...current, [field]: undefined, subIssue: undefined }));
  }

  function focusFirstError(nextErrors: CaseFormErrors) {
    const order: Array<keyof CaseFormErrors> = [
      'product',
      'issue',
      'narrative',
      'handlingNotes',
      'receivedDate',
      'company',
      'companyResponse',
      'evaluationNotes',
      'referenceCaseIds',
    ];
    const first = order.find((field) => nextErrors[field]);
    if (first) document.getElementById(FIELD_IDS[first])?.focus();
  }

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (inFlight.current) return;

    const nextErrors = validateCaseForm(values, { currentCaseId });
    setErrors(nextErrors);
    if (Object.keys(nextErrors).length) {
      focusFirstError(nextErrors);
      return;
    }

    inFlight.current = true;
    setSaving(true);
    setServerError(null);
    try {
      const detail = await onSubmit(values);
      if (mounted.current) onSuccess(detail);
    } catch (caught) {
      if (mounted.current) setServerError(toUiError(caught));
    } finally {
      inFlight.current = false;
      if (mounted.current) setSaving(false);
    }
  }

  function addPickedReference(summary: CaseSummary) {
    cacheReference(summary);
    onChange((current) => ({
      ...current,
      referenceCaseIds: addReference(current.referenceCaseIds, summary.id),
    }));
    setErrors((current) => ({ ...current, referenceCaseIds: undefined }));
  }

  const references = Array.from(values.referenceCaseIds);
  const submitLabel = mode === 'create' ? 'Save analyst case' : 'Save changes';

  return (
    <>
      <form className={styles.form} onSubmit={handleSubmit} noValidate>
        {serverError ? (
          <StatusMessage tone="error" title={serverError.title}>
            <p>{serverError.message}</p>
            {serverError.fieldErrors.length ? (
              <ul className={styles.serverFieldList}>
                {serverError.fieldErrors.map((fieldError) => (
                  <li key={`${fieldError.field}-${fieldError.code}`}>
                    <strong>{fieldLabel(fieldError.field.split(/[.[]/)[0])}:</strong>{' '}
                    {fieldError.message}
                  </li>
                ))}
              </ul>
            ) : null}
          </StatusMessage>
        ) : null}

        <Card as="section" className={styles.section}>
          <div className={styles.sectionHeading}>
            <div>
              <p className="page-eyebrow">Complaint</p>
              <h2 className={styles.sectionTitle}>Complaint details</h2>
            </div>
            <span className={styles.requiredNote}>
              Required: product, issue, narrative, and handling notes
            </span>
          </div>

          <div className={styles.fieldGrid}>
            <Select
              id={FIELD_IDS.product}
              label="Product"
              required
              value={values.product}
              onChange={(event) => changeCategory('product', event.target.value)}
              error={errors.product ?? errorForField(serverError, 'product')}
              disabled={catalogLoading}
            >
              <option value="">Select a product</option>
              {options.products.map((product) => (
                <option key={product} value={product}>{product}</option>
              ))}
            </Select>

            <Select
              id="case-sub-product"
              label="Sub-product"
              optional
              value={values.subProduct}
              onChange={(event) => changeCategory('subProduct', event.target.value)}
              error={errorForField(serverError, 'subProduct')}
              disabled={!values.product || !options.subProducts.length}
            >
              <option value="">Not specified</option>
              {options.subProducts.map((subProduct) => (
                <option key={subProduct} value={subProduct}>{subProduct}</option>
              ))}
            </Select>

            <Select
              id={FIELD_IDS.issue}
              label="Issue"
              required
              value={values.issue}
              onChange={(event) => changeCategory('issue', event.target.value)}
              error={errors.issue ?? errorForField(serverError, 'issue')}
              disabled={!values.product}
            >
              <option value="">Select an issue</option>
              {options.issues.map((issue) => (
                <option key={issue} value={issue}>{issue}</option>
              ))}
            </Select>

            <Select
              id="case-sub-issue"
              label="Sub-issue"
              optional
              value={values.subIssue}
              onChange={(event) => changeCategory('subIssue', event.target.value)}
              error={errorForField(serverError, 'subIssue')}
              disabled={!values.product || !values.issue || !options.subIssues.length}
            >
              <option value="">Not specified</option>
              {options.subIssues.map((subIssue) => (
                <option key={subIssue} value={subIssue}>{subIssue}</option>
              ))}
            </Select>
          </div>

          <Textarea
            id={FIELD_IDS.narrative}
            label="Complaint narrative"
            required
            rows={9}
            maxLength={50_000}
            value={values.narrative}
            onChange={(event) => setField('narrative', event.target.value)}
            error={errors.narrative ?? errorForField(serverError, 'narrative')}
            hint={`${textLength(values.narrative).toLocaleString('en-US')} / 50,000 characters. The narrative is stored and displayed unchanged.`}
          />
        </Card>

        <Card as="section" className={styles.section}>
          <div className={styles.sectionHeading}>
            <div>
              <p className="page-eyebrow">Optional context</p>
              <h2 className={styles.sectionTitle}>Case context</h2>
            </div>
          </div>
          <div className={styles.fieldGrid}>
            <TextInput
              id={FIELD_IDS.receivedDate}
              label="Received date"
              optional
              type="date"
              value={values.receivedDate}
              onChange={(event) => setField('receivedDate', event.target.value)}
              error={errors.receivedDate ?? errorForField(serverError, 'receivedDate')}
            />
            <TextInput
              id={FIELD_IDS.company}
              label="Company"
              optional
              maxLength={500}
              value={values.company}
              onChange={(event) => setField('company', event.target.value)}
              error={errors.company ?? errorForField(serverError, 'company')}
            />
          </div>
          <Textarea
            id={FIELD_IDS.companyResponse}
            label="Company response"
            optional
            rows={4}
            maxLength={2_000}
            value={values.companyResponse}
            onChange={(event) => setField('companyResponse', event.target.value)}
            error={errors.companyResponse ?? errorForField(serverError, 'companyResponse')}
            hint="This is an analyst-reported response and is labelled as such in the case."
          />
        </Card>

        <Card as="section" className={styles.section}>
          <div className={styles.sectionHeading}>
            <div>
              <p className="page-eyebrow">Analyst assessment</p>
              <h2 className={styles.sectionTitle}>Handling and evaluation</h2>
            </div>
          </div>

          <Textarea
            id={FIELD_IDS.handlingNotes}
            label="Handling notes"
            required
            rows={6}
            maxLength={10_000}
            value={values.handlingNotes}
            onChange={(event) => setField('handlingNotes', event.target.value)}
            error={errors.handlingNotes ?? errorForField(serverError, 'handlingNotes')}
            hint={`${textLength(values.handlingNotes).toLocaleString('en-US')} / 10,000 characters. Record what was done, not only the final response.`}
          />

          <Select
            id="case-outcome"
            label="Outcome assessment"
            value={values.outcomeAssessment}
            onChange={(event) => {
              setField('outcomeAssessment', event.target.value as OutcomeAssessment);
            }}
            error={errorForField(serverError, 'outcomeAssessment')}
            hint="An analyst judgement, not a system verification."
          >
            {OUTCOME_ASSESSMENTS.map((outcome) => (
              <option key={outcome.value} value={outcome.value}>{outcome.label}</option>
            ))}
          </Select>

          <Textarea
            id={FIELD_IDS.evaluationNotes}
            label="Evaluation notes"
            optional
            rows={4}
            maxLength={10_000}
            value={values.evaluationNotes}
            onChange={(event) => setField('evaluationNotes', event.target.value)}
            error={errors.evaluationNotes ?? errorForField(serverError, 'evaluationNotes')}
            hint={`${textLength(values.evaluationNotes).toLocaleString('en-US')} / 10,000 characters.`}
          />
        </Card>

        <Card as="section" className={styles.section}>
          <div className={styles.sectionHeading}>
            <div>
              <p className="page-eyebrow">Precedents</p>
              <h2
                className={styles.sectionTitle}
                id={FIELD_IDS.referenceCaseIds}
                tabIndex={-1}
              >
                Reference cases
              </h2>
            </div>
            <Badge tone={references.length === 20 ? 'warning' : 'neutral'}>
              {references.length} / 20
            </Badge>
          </div>
          <p className={styles.sectionCopy}>
            References connect this retained case to precedent cases in the library.
            You can add up to 20. A case cannot reference itself.
          </p>

          {references.length ? (
            <ul className={styles.referenceList}>
              {references.map((referenceId) => {
                const summary = referenceCache[referenceId];
                return (
                  <li key={referenceId} className={styles.referenceItem}>
                    <div className={styles.referenceMain}>
                      {summary ? (
                        <>
                          <div className={styles.referenceBadges}>
                            <Badge tone={summary.source === 'CFPB' ? 'neutral' : 'ink'}>
                              {sourceLabel(summary.source)}
                            </Badge>
                          </div>
                          <p className={styles.referenceTitle}>{summary.product}</p>
                          <p className={styles.referencePath}>
                            {categoryPath({
                              product: summary.product,
                              subProduct: summary.subProduct,
                              issue: summary.issue,
                              subIssue: summary.subIssue,
                            })}
                          </p>
                        </>
                      ) : (
                        <p className={`${styles.referenceTitle} ${styles.mono}`}>
                          {referenceId}
                        </p>
                      )}
                    </div>
                    <div className={styles.referenceActions}>
                      <Link
                        href={`/cases/${referenceId}`}
                        className={styles.referenceLink}
                      >
                        View
                      </Link>
                      <Button
                        variant="quiet"
                        onClick={() => {
                          onChange((current) => ({
                            ...current,
                            referenceCaseIds: removeReference(
                              current.referenceCaseIds,
                              referenceId,
                            ),
                          }));
                        }}
                        aria-label={`Remove reference ${summary?.product ?? referenceId}`}
                      >
                        Remove
                      </Button>
                    </div>
                  </li>
                );
              })}
            </ul>
          ) : (
            <p className={styles.emptyReferences}>
              No references selected. You can save the case without references.
            </p>
          )}

          {errors.referenceCaseIds ?? errorForField(serverError, 'referenceCaseIds') ? (
            <p className={styles.fieldError}>
              {errors.referenceCaseIds ?? errorForField(serverError, 'referenceCaseIds')}
            </p>
          ) : null}

          <div>
            <Button
              variant="tertiary"
              onClick={() => setPickerOpen(true)}
              disabled={references.length >= 20}
            >
              Add reference from case library
            </Button>
          </div>
        </Card>

        <div className={styles.actions}>
          <Button type="submit" busy={saving} disabled={saving}>
            {saving ? 'Saving…' : submitLabel}
          </Button>
          <LinkButton href={cancelHref} variant="tertiary">
            Cancel
          </LinkButton>
          <p className={styles.saveNote} aria-live="polite">
            {saving
              ? 'Saving once. Do not refresh or submit again.'
              : 'Saving sends the complete form. A failed save keeps every field.'}
          </p>
        </div>
      </form>

      <ReferencePicker
        open={pickerOpen}
        onClose={() => setPickerOpen(false)}
        selectedIds={values.referenceCaseIds}
        excludeId={currentCaseId}
        maxReached={references.length >= 20}
        onAdd={addPickedReference}
      />
    </>
  );
}

export function isReferenceSelected(values: CaseFormValues, id: string): boolean {
  return hasReference(values.referenceCaseIds, id);
}
