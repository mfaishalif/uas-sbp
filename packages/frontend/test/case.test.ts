import { describe, expect, it } from 'vitest';
import {
  buildSaveCaseRequest,
  categoryPath,
  EMPTY_COMPLAINT_FORM,
  sameComplaint,
  validateSaveCase,
} from '@/lib/case';
import { cfpbId, secondCfpbId } from './fixtures';

const complaint = {
  ...EMPTY_COMPLAINT_FORM,
  product: 'Checking or savings account',
  subProduct: 'Checking account',
  issue: 'Managing an account',
  narrative: 'My transfer is missing from my checking account.',
};

describe('save case payload', () => {
  it('sends the complaint, chosen response, notes, UNKNOWN outcome, and references', () => {
    const payload = buildSaveCaseRequest({
      complaint,
      companyResponse: 'Closed with monetary relief',
      handlingNotes: 'Refunded the duplicate transfer.',
      referenceCaseIds: [cfpbId, secondCfpbId],
    });

    expect(payload).toEqual({
      product: 'Checking or savings account',
      subProduct: 'Checking account',
      issue: 'Managing an account',
      subIssue: null,
      narrative: 'My transfer is missing from my checking account.',
      receivedDate: null,
      company: null,
      companyResponse: 'Closed with monetary relief',
      handlingNotes: 'Refunded the duplicate transfer.',
      outcomeAssessment: 'UNKNOWN',
      evaluationNotes: null,
      referenceCaseIds: new Set([cfpbId, secondCfpbId]),
    });
    expect(payload.referenceCaseIds).toBeInstanceOf(Set);
  });

  it('requires a response and handling notes, measured in code points', () => {
    expect(validateSaveCase({ companyResponse: '', handlingNotes: '  ' })).toEqual({
      companyResponse: 'Choose a company response.',
      handlingNotes: 'Add notes about how the case is being handled.',
    });

    const atLimit = '😀'.repeat(10_000);
    expect(validateSaveCase({ companyResponse: 'Closed with explanation', handlingNotes: atLimit }))
      .toEqual({});
    expect(
      validateSaveCase({ companyResponse: 'Closed with explanation', handlingNotes: `${atLimit}x` })
        .handlingNotes,
    ).toMatch(/10,000 characters or fewer/);
  });

  it('compares complaints field by field and formats the category path', () => {
    expect(sameComplaint(complaint, { ...complaint })).toBe(true);
    expect(sameComplaint(complaint, { ...complaint, narrative: 'Changed.' })).toBe(false);
    expect(categoryPath(complaint)).toBe(
      'Checking or savings account / Checking account / Managing an account',
    );
  });
});
