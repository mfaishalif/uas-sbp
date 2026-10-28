import { describe, expect, it } from 'vitest';
import {
  categoryOptions,
  issueOptions,
  productOptions,
  reconcileCategorySelection,
  subIssueOptions,
  subProductOptions,
  updateCategorySelection,
} from '@/lib/categories';
import { categoriesFixture } from './fixtures';

const base = {
  product: 'Checking or savings account',
  subProduct: 'Checking account',
  issue: 'Managing an account',
  subIssue: 'Deposits and withdrawals',
  narrative: 'Money was taken twice.',
};

describe('category option rules', () => {
  it('derives sorted product options and filters children by parent', () => {
    expect(productOptions(categoriesFixture)).toEqual([
      'Checking or savings account',
      'Money transfer, virtual currency, or money service',
    ]);
    expect(subProductOptions(categoriesFixture, 'Checking or savings account')).toEqual([
      'Checking account',
      'Savings account',
    ]);
    expect(issueOptions(categoriesFixture, 'Checking or savings account')).toEqual([
      'Managing an account',
      'Problem with a loan',
    ]);
    expect(
      subIssueOptions(
        categoriesFixture,
        'Checking or savings account',
        'Managing an account',
      ),
    ).toEqual(['Deposits and withdrawals']);
    expect(
      categoryOptions(categoriesFixture, {
        ...base,
        product: 'Money transfer, virtual currency, or money service',
        subProduct: '',
        issue: 'Money was not available when promised',
        subIssue: '',
      }).subProducts,
    ).toEqual(['Domestic money transfer']);
  });

  it('clears invalid descendants when a product changes', () => {
    const next = updateCategorySelection(
      categoriesFixture,
      base,
      'product',
      'Money transfer, virtual currency, or money service',
    );
    expect(next.product).toBe('Money transfer, virtual currency, or money service');
    expect(next.subProduct).toBe('');
    expect(next.issue).toBe('');
    expect(next.subIssue).toBe('');
    expect(next.narrative).toBe(base.narrative);
  });

  it('keeps a descendant only when the label remains valid under the new parent', () => {
    const keepsIssue = updateCategorySelection(
      categoriesFixture,
      base,
      'product',
      'Checking or savings account',
    );
    expect(keepsIssue.issue).toBe('Managing an account');
    expect(keepsIssue.subIssue).toBe('Deposits and withdrawals');

    const next = updateCategorySelection(
      categoriesFixture,
      base,
      'issue',
      'Problem with a loan',
    );
    expect(next.subIssue).toBe('');
    expect(next.subProduct).toBe('Checking account');
  });

  it('reconciles stale catalog values, including an empty catalog', () => {
    const stale = { ...base, product: 'A product that no longer exists' };
    expect(reconcileCategorySelection(categoriesFixture, stale)).toEqual({
      ...base,
      product: '',
      subProduct: '',
      issue: '',
      subIssue: '',
    });

    const valid = reconcileCategorySelection(categoriesFixture, base);
    expect(valid).toBe(base);
    expect(reconcileCategorySelection([], base)).toEqual({
      ...base,
      product: '',
      subProduct: '',
      issue: '',
      subIssue: '',
    });
  });
});
