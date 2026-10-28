import { ApiClientError } from '@finresolve/api-client';

export interface UiFieldError {
  field: string;
  code: string;
  message: string;
}

export interface UiError {
  code: string;
  status: number;
  title: string;
  message: string;
  fieldErrors: UiFieldError[];
}

const CODE_MESSAGES: Record<string, { title: string; message: string }> = {
  VALIDATION_ERROR: {
    title: 'Check the form',
    message: 'Some fields need attention. Review the values and try again.',
  },
  MALFORMED_REQUEST: {
    title: 'Request not understood',
    message: 'The server could not read the request. Refresh the page and try again.',
  },
  INVALID_CATEGORY: {
    title: 'Category not available',
    message: 'That category is no longer in the catalog. Reload the categories and choose again.',
  },
  INVALID_REFERENCE: {
    title: 'Reference not available',
    message: 'One or more selected case references are unavailable. Remove them and try again.',
  },
  INVALID_CSV: {
    title: 'CSV file not accepted',
    message: 'The file is not a valid CFPB CSV. Check the required header and try again.',
  },
  IMPORT_LIMIT_EXCEEDED: {
    title: 'Import is too large',
    message: 'The CSV exceeds the 5,000-record import limit. Split the file and try again.',
  },
  CASE_NOT_FOUND: {
    title: 'Case not found',
    message: 'This case is not available. It may have been removed or the link may be wrong.',
  },
  ROUTE_NOT_FOUND: {
    title: 'Page not found',
    message: 'The requested resource does not exist.',
  },
  IMMUTABLE_CFPB_CASE: {
    title: 'Archive case is read-only',
    message: 'CFPB archive cases cannot be edited. Analyst cases are the only editable cases.',
  },
  EMPTY_CATEGORY_CATALOG: {
    title: 'No categories yet',
    message: 'Import a CFPB CSV before creating analyst cases.',
  },
  KNOWLEDGE_BASE_LIMIT_EXCEEDED: {
    title: 'Knowledge base is full',
    message: 'This demo stores at most 5,000 cases. Remove no data automatically; use a fresh database for a new demo.',
  },
  PAYLOAD_TOO_LARGE: {
    title: 'Upload too large',
    message: 'The file must be 20 MiB or smaller and the whole request must stay under 21 MiB.',
  },
  METHOD_NOT_ALLOWED: {
    title: 'Action not supported',
    message: 'The server does not allow this action on this page.',
  },
  UNSUPPORTED_MEDIA_TYPE: {
    title: 'Unsupported file type',
    message: 'Send the CSV as text/csv or application/octet-stream and metadata as JSON.',
  },
  DATABASE_BUSY: {
    title: 'Database is busy',
    message: 'The demo database is handling another write. Wait a moment, then try again.',
  },
  SERVICE_UNAVAILABLE: {
    title: 'Service unavailable',
    message: 'The case service is temporarily unavailable. Try again when it is running.',
  },
  INTERNAL_ERROR: {
    title: 'Something went wrong',
    message: 'The server could not complete the request. The form contents were kept.',
  },
  TRANSPORT_ERROR: {
    title: 'Cannot reach the server',
    message: 'No readable response was received. Check that the backend is running and try again.',
  },
};

const FIELD_CODE_MESSAGES: Record<string, string> = {
  REQUIRED: 'This value is required.',
  INVALID_FORMAT: 'This value has an invalid format.',
  OUT_OF_RANGE: 'This value is outside the allowed range.',
  INVALID_VALUE: 'This value is not allowed.',
  UNKNOWN_FIELD: 'This field is not recognized.',
  DUPLICATE: 'This value is duplicated.',
  NOT_FOUND: 'The referenced value was not found.',
  TOO_LONG: 'This value is too long.',
  NO_MEANINGFUL_TOKENS: 'Add meaningful words to the narrative.',
  INVALID_CATEGORY: 'This category is not in the imported catalog.',
  IMPORT_LIMIT_EXCEEDED: 'The file exceeds an import limit.',
  PAYLOAD_TOO_LARGE: 'The file exceeds the upload size limit.',
};

export function fieldCodeMessage(code: string): string {
  return FIELD_CODE_MESSAGES[code] ?? 'This value could not be accepted.';
}

export function toUiError(error: unknown): UiError {
  if (error instanceof ApiClientError) {
    const known = CODE_MESSAGES[error.code];
    return {
      code: error.code,
      status: error.status,
      title: known?.title ?? 'Request failed',
      message: known?.message ?? 'The request could not be completed. Try again.',
      fieldErrors: error.fieldErrors.map((fieldError) => ({
        field: fieldError.field,
        code: fieldError.code,
        message: fieldCodeMessage(fieldError.code),
      })),
    };
  }

  if (error instanceof Error && error.message) {
    return {
      code: 'UNEXPECTED_ERROR',
      status: 0,
      title: 'Something went wrong',
      message: 'An unexpected error occurred. The current form contents were kept.',
      fieldErrors: [],
    };
  }

  return {
    code: 'UNEXPECTED_ERROR',
    status: 0,
    title: 'Something went wrong',
    message: 'An unexpected error occurred. The current form contents were kept.',
    fieldErrors: [],
  };
}

export function errorForField(error: UiError | null, field: string): string | undefined {
  if (!error) return undefined;
  return error.fieldErrors.find(
    (fieldError) => fieldError.field === field || fieldError.field.startsWith(`${field}[`),
  )?.message;
}

export function apiErrorMessage(code: string): { title: string; message: string } {
  return CODE_MESSAGES[code] ?? {
    title: 'Request failed',
    message: 'The request could not be completed. Try again.',
  };
}
