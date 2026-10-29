import { describe, expect, it } from 'vitest';
import {
  addReference,
  buildRetainCaseRequest,
  caseDetailToForm,
  emptyCaseForm,
  hasReference,
  removeReference,
  validateCaseForm,
} from '@/lib/case';
import { analystCase, analystId, cfpbId } from './fixtures';

describe('retention payload', () => {
  it('builds a complete replacement payload with every contract field', () => {
    const values = {
      ...emptyCaseForm(),
      product: 'Checking or savings account',
      subProduct: 'Checking account',
      issue: 'Managing an account',
      subIssue: '',
      narrative: 'My transfer is missing from my checking account.',
      receivedDate: '',
      company: '',
      companyResponse: '',
      handlingNotes: 'Reviewed transfer evidence and requested a trace.',
      outcomeAssessment: 'UNKNOWN' as const,
      evaluationNotes: '',
      referenceCaseIds: new Set([cfpbId]),
    };

    const payload = buildRetainCaseRequest(values);
    expect(payload).toEqual({
      product: 'Checking or savings account',
      subProduct: 'Checking account',
      issue: 'Managing an account',
      subIssue: null,
      narrative: 'My transfer is missing from my checking account.',
      receivedDate: null,
      company: null,
      companyResponse: null,
      handlingNotes: 'Reviewed transfer evidence and requested a trace.',
      outcomeAssessment: 'UNKNOWN',
      evaluationNotes: null,
      referenceCaseIds: new Set([cfpbId]),
    });
    expect(payload.referenceCaseIds).toBeInstanceOf(Set);
    expect(Object.keys(payload).sort()).toEqual([
      'company',
      'companyResponse',
      'evaluationNotes',
      'handlingNotes',
      'issue',
      'narrative',
      'outcomeAssessment',
      'product',
      'receivedDate',
      'referenceCaseIds',
      'subIssue',
      'subProduct',
    ]);
  });

  it('loads every detail field from an analyst case', () => {
    const values = caseDetailToForm(analystCase);
    expect(values.narrative).toBe(analystCase.narrative);
    expect(values.handlingNotes).toBe(analystCase.analystNotes.handlingNotes);
    expect(values.outcomeAssessment).toBe('PARTIALLY_RESOLVED');
    expect(values.evaluationNotes).toBe(analystCase.analystNotes.evaluationNotes);
    expect(values.referenceCaseIds).toEqual(new Set([cfpbId]));
  });

  it('rejects archive cases from the editable form model', () => {
    expect(() => caseDetailToForm({
      ...analystCase,
      source: 'CFPB',
    } as never)).toThrow(/read-only/i);
  });

  it('validates required values, limits, self-references, and dates', () => {
    const errors = validateCaseForm(
      {
        ...emptyCaseForm(),
        product: '',
        issue: '',
        narrative: ' ',
        handlingNotes: '',
        receivedDate: '2026-02-30',
      },
      { currentCaseId: cfpbId },
    );
    expect(errors.product).toBeDefined();
    expect(errors.issue).toBeDefined();
    expect(errors.narrative).toBeDefined();
    expect(errors.handlingNotes).toBeDefined();
    expect(errors.receivedDate).toBeDefined();

    const self = validateCaseForm(
      {
        ...emptyCaseForm(),
        product: 'P',
        issue: 'I',
        narrative: 'A sufficiently meaningful narrative.',
        handlingNotes: 'Handled.',
        referenceCaseIds: new Set([cfpbId]),
      },
      { currentCaseId: cfpbId },
    );
    expect(self.referenceCaseIds).toMatch(/itself/i);
  });

  it('measures every limited field in code points, not UTF-16 units', () => {
    const base = {
      ...emptyCaseForm(),
      product: 'Checking or savings account',
      issue: 'Managing an account',
      narrative: 'A valid narrative.',
      handlingNotes: 'Handled.',
    };
    const cases: Array<{
      field: 'narrative' | 'handlingNotes' | 'evaluationNotes' | 'company' | 'companyResponse';
      limit: number;
    }> = [
      { field: 'narrative', limit: 50_000 },
      { field: 'handlingNotes', limit: 10_000 },
      { field: 'evaluationNotes', limit: 10_000 },
      { field: 'company', limit: 500 },
      { field: 'companyResponse', limit: 2_000 },
    ];

    for (const { field, limit } of cases) {
      for (const unit of ['BMP', 'astral'] as const) {
        const atLimit = unit === 'BMP' ? 'x'.repeat(limit) : '😀'.repeat(limit);
        expect(Array.from(atLimit)).toHaveLength(limit);
        expect(validateCaseForm({ ...base, [field]: atLimit })[field]).toBeUndefined();

        const overLimit = `${atLimit}😀`;
        expect(Array.from(overLimit)).toHaveLength(limit + 1);
        expect(validateCaseForm({ ...base, [field]: overLimit })[field]).toMatch(
          new RegExp(`${limit.toLocaleString('en-US')}`),
        );
        // The value is never truncated by validation.
        expect(overLimit).toHaveLength(unit === 'BMP' ? limit + 2 : limit * 2 + 2);
      }
    }
  });

  it('deduplicates references case-insensitively and caps at 20', () => {
    let ids = new Set<string>();
    ids = addReference(ids, cfpbId.toUpperCase());
    expect(hasReference(ids, cfpbId)).toBe(true);
    expect(addReference(ids, cfpbId).size).toBe(1);
    expect(removeReference(ids, cfpbId).size).toBe(0);

    for (let index = 0; index < 20; index += 1) {
      ids = addReference(ids, `00000000-0000-4000-8000-${String(index).padStart(12, '0')}`);
    }
    expect(ids.size).toBe(20);
    expect(addReference(ids, analystId).size).toBe(20);
  });
});
