import type { CategoryTuple } from '@finresolve/api-client';
import type { ComplaintFormValues } from './case';

export type CategoryField = 'product' | 'subProduct' | 'issue' | 'subIssue';

const collator = new Intl.Collator('en', { sensitivity: 'base' });

function uniqueSorted(values: Array<string | null>): string[] {
  return Array.from(new Set(values.filter((value): value is string => value !== null)))
    .sort((left, right) => collator.compare(left, right));
}

export function productOptions(items: CategoryTuple[]): string[] {
  return uniqueSorted(items.map((item) => item.product));
}

export function subProductOptions(items: CategoryTuple[], product: string): string[] {
  if (!product) return [];
  return uniqueSorted(
    items.filter((item) => item.product === product).map((item) => item.subProduct),
  );
}

export function issueOptions(items: CategoryTuple[], product: string): string[] {
  if (!product) return [];
  return uniqueSorted(
    items.filter((item) => item.product === product).map((item) => item.issue),
  );
}

export function subIssueOptions(
  items: CategoryTuple[],
  product: string,
  issue: string,
): string[] {
  if (!product || !issue) return [];
  return uniqueSorted(
    items
      .filter((item) => item.product === product && item.issue === issue)
      .map((item) => item.subIssue),
  );
}

export interface CategoryOptions {
  products: string[];
  subProducts: string[];
  issues: string[];
  subIssues: string[];
}

export function categoryOptions(
  items: CategoryTuple[],
  selection: Pick<ComplaintFormValues, CategoryField>,
): CategoryOptions {
  return {
    products: productOptions(items),
    subProducts: subProductOptions(items, selection.product),
    issues: issueOptions(items, selection.product),
    subIssues: subIssueOptions(items, selection.product, selection.issue),
  };
}

/**
 * Keeps a valid parent chain and removes every descendant that is no longer valid.
 *
 * A product change keeps a child only when that exact label is still offered under
 * the new product. An issue change applies the same rule to sub-issues.
 */
export function reconcileCategorySelection<T extends ComplaintFormValues>(
  items: CategoryTuple[],
  selection: T,
): T {
  const products = productOptions(items);
  const product = products.includes(selection.product) ? selection.product : '';
  const subProducts = subProductOptions(items, product);
  const subProduct = subProducts.includes(selection.subProduct) ? selection.subProduct : '';
  const issues = issueOptions(items, product);
  const issue = issues.includes(selection.issue) ? selection.issue : '';
  const subIssues = subIssueOptions(items, product, issue);
  const subIssue = subIssues.includes(selection.subIssue) ? selection.subIssue : '';

  if (
    product === selection.product
    && subProduct === selection.subProduct
    && issue === selection.issue
    && subIssue === selection.subIssue
  ) {
    return selection;
  }
  return { ...selection, product, subProduct, issue, subIssue } as T;
}

export function updateCategorySelection<T extends ComplaintFormValues>(
  items: CategoryTuple[],
  selection: T,
  field: CategoryField,
  value: string,
): T {
  const next = { ...selection, [field]: value };
  return reconcileCategorySelection(items, next);
}
