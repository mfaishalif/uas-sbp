'use client';

import { useMemo, useRef, useState, type FormEvent } from 'react';
import type { CaseDetail, SearchResponse } from '@finresolve/api-client';
import { Badge } from '@/components/ui/Badge';
import { Button } from '@/components/ui/Button';
import { Card } from '@/components/ui/Card';
import { Select, Textarea } from '@/components/ui/FormField';
import { StatusMessage } from '@/components/ui/StatusMessage';
import { getApiClient } from '@/lib/api';
import {
  buildSaveCaseRequest,
  categoryPath,
  validateSaveCase,
  type ComplaintFormValues,
  type SaveCaseErrors,
} from '@/lib/case';
import { errorForField, toUiError, type UiError } from '@/lib/errors';
import { textLength } from '@/lib/format';
import {
  COMPANY_RESPONSES,
  formatPercent,
  recommendResponses,
} from '@/lib/responses';
import type { ComplaintErrors, ComplaintField } from './SearchForm';
import styles from './RecommendationPanel.module.css';

const COMPLAINT_FIELDS: ComplaintField[] = ['product', 'subProduct', 'issue', 'subIssue', 'narrative'];

export function RecommendationPanel({
  response,
  complaint,
  formChanged,
  onSearchAgain,
  onSaved,
  onComplaintErrors,
}: {
  response: SearchResponse;
  /** The current search form, which is what gets saved. */
  complaint: ComplaintFormValues;
  /** True when the form no longer matches the complaint these precedents came from. */
  formChanged: boolean;
  onSearchAgain: () => void;
  onSaved: (saved: CaseDetail) => void;
  onComplaintErrors: (errors: ComplaintErrors) => void;
}) {
  const recommendation = useMemo(
    () => recommendResponses(response.results),
    [response.results],
  );
  const [companyResponse, setCompanyResponse] = useState<string>(
    recommendation.recommended ?? '',
  );
  const [handlingNotes, setHandlingNotes] = useState('');
  const [errors, setErrors] = useState<SaveCaseErrors>({});
  const [saveError, setSaveError] = useState<UiError | null>(null);
  const [saving, setSaving] = useState(false);
  const inFlight = useRef(false);

  const referenceIds = response.results.map((match) => match._case.id);
  const notesLength = textLength(handlingNotes);

  async function handleSave(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (inFlight.current || formChanged) return;

    const nextErrors = validateSaveCase({ companyResponse, handlingNotes });
    setErrors(nextErrors);
    if (nextErrors.companyResponse || nextErrors.handlingNotes) {
      document
        .getElementById(nextErrors.companyResponse ? 'save-company-response' : 'save-handling-notes')
        ?.focus();
      return;
    }

    inFlight.current = true;
    setSaving(true);
    setSaveError(null);
    onComplaintErrors({});
    try {
      const saved = await getApiClient().cases.retainCase({
        retainCaseRequest: buildSaveCaseRequest({
          complaint,
          companyResponse,
          handlingNotes,
          referenceCaseIds: referenceIds,
        }),
      });
      onSaved(saved);
    } catch (caught) {
      const uiError = toUiError(caught);
      setSaveError(uiError);
      setErrors({
        companyResponse: errorForField(uiError, 'companyResponse'),
        handlingNotes: errorForField(uiError, 'handlingNotes'),
      });
      const complaintErrors: ComplaintErrors = {};
      for (const field of COMPLAINT_FIELDS) {
        const message = errorForField(uiError, field);
        if (message) complaintErrors[field] = message;
      }
      onComplaintErrors(complaintErrors);
    } finally {
      inFlight.current = false;
      setSaving(false);
    }
  }

  const mapped = new Set<string>(['companyResponse', 'handlingNotes', ...COMPLAINT_FIELDS]);
  const otherFieldErrors = saveError?.fieldErrors.filter(
    (fieldError) => !mapped.has(fieldError.field),
  ) ?? [];
  const complaintFieldErrors = saveError?.fieldErrors.filter(
    (fieldError) => (COMPLAINT_FIELDS as string[]).includes(fieldError.field),
  ) ?? [];

  return (
    <section className={styles.panel} aria-labelledby="recommendation-title">
      <Card className={styles.vote}>
        <p className="page-eyebrow">Reuse</p>
        <h2 className={styles.title} id="recommendation-title">Response recommendation</h2>
        <p className={styles.intro}>
          Each precedent votes for its company response, weighted by its total score.
        </p>

        <ul className={styles.shares}>
          {recommendation.shares.map((share) => {
            const isRecommended = share.response === recommendation.recommended;
            return (
              <li key={share.response} className={styles.share}>
                <div className={styles.shareHead}>
                  <span className={styles.shareName}>{share.response}</span>
                  {isRecommended ? <Badge tone="positive">Recommended</Badge> : null}
                  <span className={styles.sharePercent}>{formatPercent(share.percent)}</span>
                </div>
                <div
                  className={styles.bar}
                  role="img"
                  aria-label={`${share.response}: ${formatPercent(share.percent)}`}
                >
                  <span
                    className={isRecommended ? styles.fillRecommended : styles.fill}
                    style={{ width: `${Math.min(100, share.percent)}%` }}
                  />
                </div>
                <p className={styles.shareMeta}>
                  {share.precedentCount === 0
                    ? 'No precedent shown'
                    : `${share.precedentCount} ${share.precedentCount === 1 ? 'precedent' : 'precedents'}`}
                </p>
              </li>
            );
          })}
        </ul>

        {recommendation.otherPercent > 0.05 ? (
          <p className={styles.note}>
            {formatPercent(recommendation.otherPercent)} of the weight comes from
            precedents with another or no recorded response.
          </p>
        ) : null}
        {recommendation.recommended === null ? (
          <p className={styles.note}>
            No recommendation: no precedent with one of these responses was found.
          </p>
        ) : null}
        <p className={styles.formula}>
          Share = Σ total score of precedents with that response ÷ Σ total score of all
          precedents shown × 100%.
        </p>
      </Card>

      <Card className={styles.save}>
        <p className="page-eyebrow">Revise &amp; retain</p>
        <h2 className={styles.title}>Save this case</h2>

        {formChanged ? (
          <StatusMessage tone="info" title="The complaint changed after this search">
            <p>Search again so the precedents and recommendation match what you save.</p>
            <Button variant="tertiary" onClick={onSearchAgain}>Search again</Button>
          </StatusMessage>
        ) : null}

        <form className={styles.form} onSubmit={handleSave} noValidate>
          <Select
            id="save-company-response"
            label="Company response"
            value={companyResponse}
            onChange={(event) => setCompanyResponse(event.target.value)}
            error={errors.companyResponse}
          >
            <option value="">Choose a response</option>
            {COMPANY_RESPONSES.map((option) => (
              <option key={option} value={option}>
                {option === recommendation.recommended ? `${option} (Recommended)` : option}
              </option>
            ))}
          </Select>

          <Textarea
            id="save-handling-notes"
            label="Handling notes"
            value={handlingNotes}
            onChange={(event) => setHandlingNotes(event.target.value)}
            error={errors.handlingNotes}
            hint={`${notesLength.toLocaleString('en-US')} / 10,000 characters. What you did, and why this response fits.`}
            rows={5}
            required
          />

          <dl className={styles.summary}>
            <div>
              <dt>Complaint</dt>
              <dd>{categoryPath(complaint) || 'Not selected'}</dd>
            </div>
            <div>
              <dt>References</dt>
              <dd>
                {referenceIds.length === 0
                  ? 'None (no precedent shown)'
                  : `${referenceIds.length} ${referenceIds.length === 1 ? 'precedent' : 'precedents'} shown above`}
              </dd>
            </div>
          </dl>

          {saveError ? (
            <StatusMessage tone="error" title={saveError.title}>
              <p>{saveError.message}</p>
              {complaintFieldErrors.length || otherFieldErrors.length ? (
                <ul className={styles.errorList}>
                  {complaintFieldErrors.map((fieldError) => (
                    <li key={`${fieldError.field}-${fieldError.code}`}>
                      <strong>{fieldError.field}</strong> (in the complaint form): {fieldError.message}
                    </li>
                  ))}
                  {otherFieldErrors.map((fieldError) => (
                    <li key={`${fieldError.field}-${fieldError.code}`}>
                      <strong>{fieldError.field}:</strong> {fieldError.message}
                    </li>
                  ))}
                </ul>
              ) : null}
            </StatusMessage>
          ) : null}

          <Button type="submit" busy={saving} disabled={formChanged} fullWidth>
            {saving ? 'Saving…' : 'Save case'}
          </Button>
        </form>
      </Card>
    </section>
  );
}
