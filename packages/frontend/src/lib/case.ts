import type {
  AnalystCase,
  CaseDetail,
  CaseSummary,
  RetainCaseRequest,
  RetainCaseRequestOutcomeAssessmentEnum,
} from '@finresolve/api-client';

export type OutcomeAssessment = RetainCaseRequestOutcomeAssessmentEnum;

export type DraftUpdater<T> = Partial<T> | ((current: T) => T);

export const OUTCOME_ASSESSMENTS: readonly {
  value: OutcomeAssessment;
  label: string;
}[] = [
  { value: 'UNKNOWN', label: 'Not assessed yet' },
  { value: 'RESOLVED', label: 'Resolved' },
  { value: 'PARTIALLY_RESOLVED', label: 'Partially resolved' },
  { value: 'UNRESOLVED', label: 'Unresolved' },
];

export interface ComplaintFormValues {
  product: string;
  subProduct: string;
  issue: string;
  subIssue: string;
  narrative: string;
}

export interface CaseFormValues extends ComplaintFormValues {
  receivedDate: string;
  company: string;
  companyResponse: string;
  handlingNotes: string;
  outcomeAssessment: OutcomeAssessment;
  evaluationNotes: string;
  referenceCaseIds: Set<string>;
}

export type CaseFormField = keyof Omit<CaseFormValues, 'referenceCaseIds'> | 'referenceCaseIds';

export const EMPTY_COMPLAINT_FORM: ComplaintFormValues = {
  product: '',
  subProduct: '',
  issue: '',
  subIssue: '',
  narrative: '',
};

export function emptyCaseForm(): CaseFormValues {
  return {
    ...EMPTY_COMPLAINT_FORM,
    receivedDate: '',
    company: '',
    companyResponse: '',
    handlingNotes: '',
    outcomeAssessment: 'UNKNOWN',
    evaluationNotes: '',
    referenceCaseIds: new Set<string>(),
  };
}

export function nullable(value: string): string | null {
  return value === '' ? null : value;
}

/**
 * Builds the complete replacement payload used by both POST and PUT.
 *
 * Optional values are always present and empty strings become null. The generated
 * client uses a Set for references and serializes it back to a JSON array.
 */
export function buildRetainCaseRequest(values: CaseFormValues): RetainCaseRequest {
  return {
    product: values.product,
    subProduct: nullable(values.subProduct),
    issue: values.issue,
    subIssue: nullable(values.subIssue),
    narrative: values.narrative,
    receivedDate: nullable(values.receivedDate),
    company: nullable(values.company),
    companyResponse: nullable(values.companyResponse),
    handlingNotes: values.handlingNotes,
    outcomeAssessment: values.outcomeAssessment,
    evaluationNotes: nullable(values.evaluationNotes),
    referenceCaseIds: new Set(values.referenceCaseIds),
  };
}

export function caseDetailToForm(detail: CaseDetail): CaseFormValues {
  if (detail.source !== 'ANALYST') {
    throw new Error('Archive cases are read-only.');
  }
  return analystCaseToForm(detail);
}

export function analystCaseToForm(detail: AnalystCase): CaseFormValues {
  return {
    product: detail.product,
    subProduct: detail.subProduct ?? '',
    issue: detail.issue,
    subIssue: detail.subIssue ?? '',
    narrative: detail.narrative,
    receivedDate: detail.receivedDate ?? '',
    company: detail.company ?? '',
    companyResponse: detail.companyResponse ?? '',
    handlingNotes: detail.analystNotes.handlingNotes,
    outcomeAssessment: detail.analystNotes.outcomeAssessment,
    evaluationNotes: detail.analystNotes.evaluationNotes ?? '',
    referenceCaseIds: new Set(detail.referenceCaseIds),
  };
}

export function caseDetailToSummary(detail: CaseDetail): CaseSummary {
  return {
    id: detail.id,
    source: detail.source,
    product: detail.product,
    subProduct: detail.subProduct,
    issue: detail.issue,
    subIssue: detail.subIssue,
    receivedDate: detail.receivedDate,
    company: detail.company,
    companyResponse: detail.companyResponse,
    createdAt: detail.createdAt,
    updatedAt: detail.updatedAt,
  };
}

export interface CaseFormErrors {
  product?: string;
  issue?: string;
  narrative?: string;
  handlingNotes?: string;
  evaluationNotes?: string;
  company?: string;
  companyResponse?: string;
  receivedDate?: string;
  referenceCaseIds?: string;
}

export function validateCaseForm(
  values: CaseFormValues,
  options: { currentCaseId?: string } = {},
): CaseFormErrors {
  const errors: CaseFormErrors = {};

  if (!values.product.trim()) errors.product = 'Choose a product.';
  if (!values.issue.trim()) errors.issue = 'Choose an issue.';
  if (!values.narrative.trim()) {
    errors.narrative = 'Describe the complaint narrative.';
  } else if (Array.from(values.narrative).length > 50_000) {
    errors.narrative = 'The narrative must be 50,000 characters or fewer.';
  }
  if (!values.handlingNotes.trim()) {
    errors.handlingNotes = 'Add notes about how the case was handled.';
  } else if (Array.from(values.handlingNotes).length > 10_000) {
    errors.handlingNotes = 'Handling notes must be 10,000 characters or fewer.';
  }
  if (Array.from(values.evaluationNotes).length > 10_000) {
    errors.evaluationNotes = 'Evaluation notes must be 10,000 characters or fewer.';
  }
  if (Array.from(values.company).length > 500) {
    errors.company = 'Company must be 500 characters or fewer.';
  }
  if (Array.from(values.companyResponse).length > 2_000) {
    errors.companyResponse = 'Company response must be 2,000 characters or fewer.';
  }
  if (values.receivedDate && !isCalendarDate(values.receivedDate)) {
    errors.receivedDate = 'Enter a valid received date.';
  }
  if (values.referenceCaseIds.size > 20) {
    errors.referenceCaseIds = 'Select no more than 20 references.';
  } else if (options.currentCaseId) {
    const self = values.referenceCaseIds.has(options.currentCaseId)
      || Array.from(values.referenceCaseIds).some(
        (id) => id.toLowerCase() === options.currentCaseId!.toLowerCase(),
      );
    if (self) errors.referenceCaseIds = 'A case cannot reference itself.';
  }

  return errors;
}

export function isCalendarDate(value: string): boolean {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) return false;
  const date = new Date(`${value}T00:00:00Z`);
  if (Number.isNaN(date.getTime())) return false;
  return date.toISOString().slice(0, 10) === value;
}

export function hasReference(ids: Set<string>, id: string): boolean {
  const normalized = id.toLowerCase();
  return Array.from(ids).some((candidate) => candidate.toLowerCase() === normalized);
}

export function addReference(ids: Set<string>, id: string, limit = 20): Set<string> {
  if (ids.size >= limit || hasReference(ids, id)) return new Set(ids);
  return new Set([...ids, id]);
}

export function removeReference(ids: Set<string>, id: string): Set<string> {
  const normalized = id.toLowerCase();
  return new Set(Array.from(ids).filter((candidate) => candidate.toLowerCase() !== normalized));
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
