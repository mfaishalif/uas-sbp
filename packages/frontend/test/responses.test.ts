import { describe, expect, it } from 'vitest';
import { COMPANY_RESPONSES, recommendResponses, type ScoredPrecedent } from '@/lib/responses';

function precedent(score: number, companyResponse: string | null): ScoredPrecedent {
  return { score, _case: { companyResponse } };
}

function percents(precedents: ScoredPrecedent[]) {
  return Object.fromEntries(
    recommendResponses(precedents).shares.map((share) => [share.response, share.percent]),
  );
}

describe('similarity-weighted response recommendation', () => {
  it('keeps the three CFPB response strings exactly', () => {
    expect(COMPANY_RESPONSES).toEqual([
      'Closed with explanation',
      'Closed with monetary relief',
      'Closed with non-monetary relief',
    ]);
  });

  it('divides each response score sum by the score sum of every precedent shown', () => {
    const precedents = [
      precedent(0.81, 'Closed with explanation'),
      precedent(0.62, 'Closed with monetary relief'),
      precedent(0.55, 'Closed with explanation'),
    ];
    const result = recommendResponses(precedents);

    expect(result.totalWeight).toBeCloseTo(1.98, 10);
    expect(percents(precedents)['Closed with explanation']).toBeCloseTo((1.36 / 1.98) * 100, 10);
    expect(percents(precedents)['Closed with monetary relief']).toBeCloseTo((0.62 / 1.98) * 100, 10);
    expect(percents(precedents)['Closed with non-monetary relief']).toBe(0);
    expect(result.recommended).toBe('Closed with explanation');
    expect(result.shares.map((share) => share.precedentCount)).toEqual([2, 1, 0]);
    expect(result.otherPercent).toBeCloseTo(0, 10);
  });

  it('breaks a tie in favour of the response held by the highest-scoring precedent', () => {
    // Explanation: 0.4 + 0.3 = 0.7. Monetary: 0.7 from one stronger precedent.
    const precedents = [
      precedent(0.4, 'Closed with explanation'),
      precedent(0.7, 'Closed with monetary relief'),
      precedent(0.3, 'Closed with explanation'),
    ];
    const result = recommendResponses(precedents);

    expect(percents(precedents)['Closed with explanation']).toBeCloseTo(50, 10);
    expect(percents(precedents)['Closed with monetary relief']).toBeCloseTo(50, 10);
    expect(result.recommended).toBe('Closed with monetary relief');
  });

  it('keeps the server order when tied precedents also have equal scores', () => {
    const result = recommendResponses([
      precedent(0.6, 'Closed with non-monetary relief'),
      precedent(0.6, 'Closed with explanation'),
    ]);
    expect(result.recommended).toBe('Closed with non-monetary relief');
  });

  it('recommends nothing and reports 0% everywhere when there are no precedents', () => {
    const result = recommendResponses([]);
    expect(result.recommended).toBeNull();
    expect(result.totalWeight).toBe(0);
    expect(result.shares.map((share) => share.percent)).toEqual([0, 0, 0]);
    expect(result.otherPercent).toBe(0);
  });

  it('gives 100% to the only response present', () => {
    const precedents = [
      precedent(0.9, 'Closed with non-monetary relief'),
      precedent(0.52, 'Closed with non-monetary relief'),
    ];
    const result = recommendResponses(precedents);
    expect(percents(precedents)).toEqual({
      'Closed with explanation': 0,
      'Closed with monetary relief': 0,
      'Closed with non-monetary relief': 100,
    });
    expect(result.recommended).toBe('Closed with non-monetary relief');
  });

  it('counts precedents with another response in the denominator only', () => {
    const precedents = [
      precedent(0.8, 'Closed with explanation'),
      precedent(0.2, 'Funds were released after a trace.'),
    ];
    const result = recommendResponses(precedents);
    expect(percents(precedents)['Closed with explanation']).toBeCloseTo(80, 10);
    expect(result.otherPercent).toBeCloseTo(20, 10);
    expect(result.recommended).toBe('Closed with explanation');
  });

  it('recommends nothing when no precedent recorded one of the three responses', () => {
    const result = recommendResponses([precedent(0.7, null), precedent(0.6, 'Other')]);
    expect(result.recommended).toBeNull();
    expect(result.shares.map((share) => share.percent)).toEqual([0, 0, 0]);
    expect(result.otherPercent).toBeCloseTo(100, 10);
  });
});
