'use client';

import { useRef, useState, type ChangeEvent, type FormEvent } from 'react';
import type { ImportReport } from '@finresolve/api-client';
import { Badge } from '@/components/ui/Badge';
import { Button } from '@/components/ui/Button';
import { Card } from '@/components/ui/Card';
import { TextInput } from '@/components/ui/FormField';
import { StatusMessage } from '@/components/ui/StatusMessage';
import { useAppState } from '@/contexts/AppState';
import { useCatalog } from '@/contexts/CatalogContext';
import { getApiClient } from '@/lib/api';
import {
  EMPTY_IMPORT_FORM,
  MAX_FILE_BYTES,
  toImportMetadata,
  validateImportForm,
  type ImportFormErrors,
  type ImportFormValues,
} from '@/lib/imports';
import {
  fieldCodeMessage,
  toUiError,
  type UiError,
} from '@/lib/errors';
import { formatDateTime } from '@/lib/format';
import styles from './ImportPage.module.css';

function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KiB`;
  return `${(bytes / (1024 * 1024)).toFixed(2)} MiB`;
}

export function ImportPage() {
  const { notifyDataChanged } = useAppState();
  const { refresh: refreshCatalog } = useCatalog();
  const [values, setValues] = useState<ImportFormValues>({ ...EMPTY_IMPORT_FORM });
  const [file, setFile] = useState<File | null>(null);
  const [errors, setErrors] = useState<ImportFormErrors>({});
  const [error, setError] = useState<UiError | null>(null);
  const [report, setReport] = useState<ImportReport | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const inFlight = useRef(false);
  const fileInput = useRef<HTMLInputElement>(null);

  function setValue(field: keyof ImportFormValues, value: string) {
    setValues((current) => ({ ...current, [field]: value }));
    setErrors((current) => ({ ...current, [field]: undefined }));
  }

  function onFileChange(event: ChangeEvent<HTMLInputElement>) {
    const selected = event.target.files?.[0] ?? null;
    setFile(selected);
    setErrors((current) => ({ ...current, file: undefined }));
    if (selected && !values.sourceFileName.trim()) {
      setValue('sourceFileName', selected.name);
    }
  }

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (inFlight.current) return;

    const nextErrors = validateImportForm(file, values);
    setErrors(nextErrors);
    if (Object.keys(nextErrors).length) {
      const first = ['file', 'archiveUrl', 'sourceFileName', 'periodStart', 'periodEnd']
        .find((field) => nextErrors[field as keyof ImportFormErrors]);
      if (first === 'file') fileInput.current?.focus();
      else if (first) document.getElementById(`import-${first}`)?.focus();
      return;
    }
    if (!file) return;

    inFlight.current = true;
    setSubmitting(true);
    setError(null);
    setReport(null);

    try {
      const response = await getApiClient().imports.importCfpb({
        file,
        metadata: toImportMetadata(values),
      });
      setReport(response);
      notifyDataChanged();
      await refreshCatalog();
    } catch (caught) {
      setError(toUiError(caught));
    } finally {
      inFlight.current = false;
      setSubmitting(false);
    }
  }

  function startAnotherImport() {
    setReport(null);
    setFile(null);
    setValues({ ...EMPTY_IMPORT_FORM });
    setErrors({});
    setError(null);
    if (fileInput.current) fileInput.current.value = '';
  }

  return (
    <div className={`fin-container ${styles.page}`}>
      <header className={styles.header}>
        <p className="page-eyebrow">Knowledge base input</p>
        <h1 className={styles.title}>Import CFPB archive data</h1>
        <p className={styles.lead}>
          Upload a CFPB narrative CSV with its archive provenance. Imports are
          synchronous: the report shows every processed, imported, duplicate, and
          rejected record before the catalog is refreshed.
        </p>
      </header>

      <div className={styles.layout}>
        <Card as="section" className={styles.formCard}>
          <h2 className={styles.sectionTitle}>Upload CSV</h2>
          <p className={styles.sectionCopy}>
            The file must use the CFPB header, contain at most 5,000 records, and stay
            at or below 20 MiB. Send a metadata record for the exact archive source.
          </p>

          <form className={styles.form} onSubmit={handleSubmit} noValidate>
            <div className={styles.fileField}>
              <label className={styles.fileLabel} htmlFor="import-file">
                CSV file
              </label>
              <input
                ref={fileInput}
                id="import-file"
                name="file"
                type="file"
                accept=".csv,text/csv"
                onChange={onFileChange}
                className={styles.fileInput}
                aria-invalid={errors.file ? true : undefined}
                aria-describedby={errors.file ? 'import-file-error' : 'import-file-hint'}
                required
              />
              <p className={styles.hint} id="import-file-hint">
                Maximum {formatBytes(MAX_FILE_BYTES)}. {file
                  ? `${file.name} · ${formatBytes(file.size)} selected`
                  : 'No file selected.'}
              </p>
              {errors.file ? (
                <p className={styles.fieldError} id="import-file-error">{errors.file}</p>
              ) : null}
            </div>

            <TextInput
              id="import-archiveUrl"
              label="Archive URL"
              type="url"
              required
              placeholder="https://example.gov/archive.zip"
              value={values.archiveUrl}
              onChange={(event) => setValue('archiveUrl', event.target.value)}
              error={errors.archiveUrl}
              hint="HTTPS URL recorded as provenance only. The server does not download it."
            />

            <TextInput
              id="import-sourceFileName"
              label="Source file name"
              required
              value={values.sourceFileName}
              onChange={(event) => setValue('sourceFileName', event.target.value)}
              error={errors.sourceFileName}
              hint="A file name only, without folders, dot segments, or drive prefixes."
            />

            <div className={styles.dateGrid}>
              <TextInput
                id="import-periodStart"
                label="Period start"
                type="date"
                required
                value={values.periodStart}
                onChange={(event) => setValue('periodStart', event.target.value)}
                error={errors.periodStart}
              />
              <TextInput
                id="import-periodEnd"
                label="Period end"
                type="date"
                required
                value={values.periodEnd}
                onChange={(event) => setValue('periodEnd', event.target.value)}
                error={errors.periodEnd}
              />
            </div>

            {error ? (
              <StatusMessage tone="error" title={error.title}>
                <p>{error.message}</p>
                {error.fieldErrors.length ? (
                  <ul className={styles.errorList}>
                    {error.fieldErrors.map((fieldError) => (
                      <li key={`${fieldError.field}-${fieldError.code}`}>
                        <strong>{fieldError.field}:</strong> {fieldError.message}
                      </li>
                    ))}
                  </ul>
                ) : null}
              </StatusMessage>
            ) : null}

            <div className={styles.actions}>
              <Button type="submit" busy={submitting} fullWidth>
                {submitting ? 'Importing CSV…' : 'Import CSV'}
              </Button>
              <p className={styles.saveNote} aria-live="polite">
                {submitting
                  ? 'One import is running. Keep this page open until the report arrives.'
                  : 'If the backend cannot be reached, the selected file and metadata stay in the form.'}
              </p>
            </div>
          </form>
        </Card>

        <div className={styles.reportColumn}>
          {report ? (
            <Card as="section" className={styles.report}>
              <div className={styles.reportHeading}>
                <div>
                  <p className="page-eyebrow">Import report</p>
                  <h2 className={styles.sectionTitle}>
                    {report.replayed ? 'Replay detected' : 'Import completed'}
                  </h2>
                </div>
                <Badge tone={report.replayed ? 'warning' : 'positive'}>
                  {report.replayed ? 'Replay' : 'New batch'}
                </Badge>
              </div>

              <StatusMessage
                tone={report.replayed ? 'info' : 'success'}
                title={report.replayed ? 'Existing batch returned' : 'Batch stored'}
              >
                <p>
                  {report.replayed
                    ? 'These bytes and metadata match an existing import. The original batch report is shown; no new rows were added.'
                    : 'The batch was stored. Review rejected rows before treating the import as complete.'}
                </p>
              </StatusMessage>

              <dl className={styles.counts}>
                <div>
                  <dt>Processed</dt>
                  <dd>{report.processed.toLocaleString('en-US')}</dd>
                </div>
                <div>
                  <dt>Imported</dt>
                  <dd>{report.imported.toLocaleString('en-US')}</dd>
                </div>
                <div>
                  <dt>Duplicates</dt>
                  <dd>{report.duplicates.toLocaleString('en-US')}</dd>
                </div>
                <div>
                  <dt>Rejected</dt>
                  <dd>{report.rejected.toLocaleString('en-US')}</dd>
                </div>
              </dl>

              {report.rejected > 0 ? (
                <StatusMessage tone="warning" title={`${report.rejected} record${report.rejected === 1 ? '' : 's'} rejected`}>
                  <p>
                    Rejected records were not stored. The examples below explain the
                    first error found in each rejected record.
                  </p>
                </StatusMessage>
              ) : null}

              {report.errors.length ? (
                <div className={styles.errorTableWrap}>
                  <table className={styles.errorTable}>
                    <caption>Sample row errors</caption>
                    <thead>
                      <tr>
                        <th scope="col">Record</th>
                        <th scope="col">Field</th>
                        <th scope="col">Problem</th>
                      </tr>
                    </thead>
                    <tbody>
                      {report.errors.map((rowError) => (
                        <tr key={`${rowError.recordNumber}-${rowError.field}-${rowError.code}`}>
                          <td>{rowError.recordNumber}</td>
                          <td>{rowError.field}</td>
                          <td>
                            <span className={styles.errorCode}>{rowError.code}</span>
                            <span>{fieldCodeMessage(rowError.code)}</span>
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                  {report.errorsTruncated ? (
                    <p className={styles.truncated}>
                      Only the first 20 row errors are shown.
                    </p>
                  ) : null}
                </div>
              ) : null}

              <dl className={styles.reportMeta}>
                <div><dt>Batch ID</dt><dd className={styles.mono}>{report.batchId}</dd></div>
                <div><dt>Imported at</dt><dd>{formatDateTime(report.importedAt)}</dd></div>
                <div><dt>Source file</dt><dd>{report.metadata.sourceFileName}</dd></div>
                <div>
                  <dt>Period</dt>
                  <dd>{report.metadata.periodStart} – {report.metadata.periodEnd}</dd>
                </div>
                <div>
                  <dt>Archive URL</dt>
                  <dd>
                    <a href={report.metadata.archiveUrl} target="_blank" rel="noreferrer noopener">
                      {report.metadata.archiveUrl}
                    </a>
                  </dd>
                </div>
                <div><dt>CSV SHA-256</dt><dd className={styles.mono}>{report.csvSha256}</dd></div>
              </dl>

              <Button variant="tertiary" onClick={startAnotherImport}>
                Import another file
              </Button>
            </Card>
          ) : (
            <Card className={styles.emptyReport}>
              <span className={styles.emptyMark} aria-hidden="true">↥</span>
              <h2 className={styles.emptyTitle}>The report appears here</h2>
              <p>
                After an import, this panel shows processed, imported, duplicate, and
                rejected counts, sample row errors, replay status, and the exact
                provenance stored with the batch.
              </p>
            </Card>
          )}
        </div>
      </div>
    </div>
  );
}
