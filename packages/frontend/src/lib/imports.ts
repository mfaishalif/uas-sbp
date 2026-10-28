import type { ImportMetadata } from '@finresolve/api-client';

export const MAX_FILE_BYTES = 20 * 1024 * 1024;
export const MAX_MULTIPART_BYTES = 21 * 1024 * 1024;

export interface ImportFormValues {
  archiveUrl: string;
  sourceFileName: string;
  periodStart: string;
  periodEnd: string;
}

export const EMPTY_IMPORT_FORM: ImportFormValues = {
  archiveUrl: '',
  sourceFileName: '',
  periodStart: '',
  periodEnd: '',
};

export type ImportFormErrors = Partial<Record<keyof ImportFormValues | 'file', string>>;

export function isHttpsUrl(value: string): boolean {
  try {
    return new URL(value).protocol === 'https:';
  } catch {
    return false;
  }
}

export function isSafeSourceFileName(value: string): boolean {
  const trimmed = value.trim();
  if (!trimmed || trimmed === '.' || trimmed === '..') return false;
  if (trimmed.includes('/') || trimmed.includes('\\')) return false;
  if (/^[A-Za-z]:/.test(trimmed)) return false;
  return !/[\u0000]/.test(trimmed);
}

export function isIsoCalendarDate(value: string): boolean {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) return false;
  const date = new Date(`${value}T00:00:00Z`);
  if (Number.isNaN(date.getTime())) return false;
  return date.toISOString().slice(0, 10) === value;
}

export function validateImportForm(
  file: File | null,
  values: ImportFormValues,
): ImportFormErrors {
  const errors: ImportFormErrors = {};
  if (!file) {
    errors.file = 'Choose a CSV file to import.';
  } else if (file.size > MAX_FILE_BYTES) {
    errors.file = 'The CSV must be 20 MiB (20,971,520 bytes) or smaller.';
  }

  if (!values.archiveUrl.trim()) {
    errors.archiveUrl = 'Enter the archive URL.';
  } else if (!isHttpsUrl(values.archiveUrl.trim())) {
    errors.archiveUrl = 'Enter a valid HTTPS archive URL.';
  }

  if (!values.sourceFileName.trim()) {
    errors.sourceFileName = 'Enter the source file name.';
  } else if (!isSafeSourceFileName(values.sourceFileName)) {
    errors.sourceFileName = 'Use a file name without slashes, dot segments, or a drive prefix.';
  }

  if (!values.periodStart) {
    errors.periodStart = 'Enter the period start.';
  } else if (!isIsoCalendarDate(values.periodStart)) {
    errors.periodStart = 'Use a valid YYYY-MM-DD date.';
  }

  if (!values.periodEnd) {
    errors.periodEnd = 'Enter the period end.';
  } else if (!isIsoCalendarDate(values.periodEnd)) {
    errors.periodEnd = 'Use a valid YYYY-MM-DD date.';
  }

  if (
    !errors.periodStart
    && !errors.periodEnd
    && values.periodStart
    && values.periodEnd
    && values.periodEnd < values.periodStart
  ) {
    errors.periodEnd = 'The period end cannot be before the period start.';
  }

  if (file && file.size <= MAX_FILE_BYTES) {
    const metadataBytes = new TextEncoder().encode(JSON.stringify(values)).length;
    if (file.size + metadataBytes > MAX_MULTIPART_BYTES) {
      errors.file = 'The upload is larger than the 21 MiB request limit.';
    }
  }

  return errors;
}

export function toImportMetadata(values: ImportFormValues): ImportMetadata {
  return {
    archiveUrl: values.archiveUrl.trim(),
    sourceFileName: values.sourceFileName.trim(),
    periodStart: values.periodStart,
    periodEnd: values.periodEnd,
  };
}
