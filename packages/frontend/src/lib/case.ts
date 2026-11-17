import type { RetainCaseRequest } from '@finresolve/api-client';
import { textLength } from './format';

export type DraftUpdater<T> = Partial<T> | ((current: T) => T);

export const NARRATIVE_LIMIT = 50_000;
export const HANDLING_NOTES_LIMIT = 10_000;

export interface ComplaintFormValues {
  product: string;
  subProduct: string;
  issue: string;
  subIssue: string;
  narrative: string;
}

export const EMPTY_COMPLAINT_FORM: ComplaintFormValues = {
  product: '',
  subProduct: '',
  issue: '',
  subIssue: '',
  narrative: '',
};

export function sameComplaint(left: ComplaintFormValues, right: ComplaintFormValues): boolean {
  return left.product === right.product
    && left.subProduct === right.subProduct
    && left.issue === right.issue
    && left.subIssue === right.subIssue
    && left.narrative === right.narrative;
}

export function nullable(value: string): string | null {
  return value === '' ? null : value;
}

export interface SaveCaseValues {
  complaint: ComplaintFormValues;
  companyResponse: string;
  handlingNotes: string;
  referenceCaseIds: readonly string[];
}

/**
 * Builds the POST /cases payload. A newly retained case has no known outcome yet,
 * so the assessment is always UNKNOWN; the references are the precedents shown.
 */
export function buildSaveCaseRequest(values: SaveCaseValues): RetainCaseRequest {
  return {
    product: values.complaint.product,
    subProduct: nullable(values.complaint.subProduct),
    issue: values.complaint.issue,
    subIssue: nullable(values.complaint.subIssue),
    narrative: values.complaint.narrative,
    receivedDate: null,
    company: null,
    companyResponse: nullable(values.companyResponse),
    handlingNotes: values.handlingNotes,
    outcomeAssessment: 'UNKNOWN',
    evaluationNotes: null,
    referenceCaseIds: new Set(values.referenceCaseIds),
  };
}

export interface SaveCaseErrors {
  companyResponse?: string;
  handlingNotes?: string;
}

export function validateSaveCase(
  values: Pick<SaveCaseValues, 'companyResponse' | 'handlingNotes'>,
): SaveCaseErrors {
  const errors: SaveCaseErrors = {};
  if (!values.companyResponse) {
    errors.companyResponse = 'Choose a company response.';
  }
  if (!values.handlingNotes.trim()) {
    errors.handlingNotes = 'Add notes about how the case is being handled.';
  } else if (textLength(values.handlingNotes) > HANDLING_NOTES_LIMIT) {
    errors.handlingNotes = 'Handling notes must be 10,000 characters or fewer.';
  }
  return errors;
}

export function categoryPath(values: {
  product: string;
  subProduct?: string | null;
  issue: string;
  subIssue?: string | null;
}): string {
  return [values.product, values.subProduct, values.issue, values.subIssue]
    .filter((value): value is string => Boolean(value))
    .join(' / ');
}
