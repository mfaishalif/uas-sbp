'use client';

import { useEffect, useState, type FormEvent } from 'react';
import { Button } from '@/components/ui/Button';
import { LoadingState } from '@/components/ui/LoadingState';
import { Select, Textarea } from '@/components/ui/FormField';
import { StatusMessage } from '@/components/ui/StatusMessage';
import { useAppState } from '@/contexts/AppState';
import { useCatalog } from '@/contexts/CatalogContext';
import { NARRATIVE_LIMIT, type ComplaintFormValues } from '@/lib/case';
import {
  categoryOptions,
  reconcileCategorySelection,
  updateCategorySelection,
  type CategoryField,
} from '@/lib/categories';
import { textLength } from '@/lib/format';
import { SEARCH_FORM_ANCHOR } from './FocusFormLink';
import styles from './SearchForm.module.css';

export const SEARCH_FORM_ID = 'search-form';

export type ComplaintField = 'product' | 'subProduct' | 'issue' | 'subIssue' | 'narrative';
export type ComplaintErrors = Partial<Record<ComplaintField, string>>;

function sameCategories(left: ComplaintFormValues, right: ComplaintFormValues): boolean {
  return left.product === right.product
    && left.subProduct === right.subProduct
    && left.issue === right.issue
    && left.subIssue === right.subIssue;
}

const FIELD_IDS: Record<ComplaintField, string> = {
  product: 'search-product',
  subProduct: 'search-sub-product',
  issue: 'search-issue',
  subIssue: 'search-sub-issue',
  narrative: 'search-narrative',
};

/**
 * The hero widget. Owns validation and category reconciliation; the parent
 * owns the request so results and the save panel share one in-flight guard.
 */
export function SearchForm({
  searching,
  onSearch,
  serverErrors,
}: {
  searching: boolean;
  onSearch: (query: ComplaintFormValues) => void;
  /** Field errors reported by the API (search or save) for complaint fields. */
  serverErrors: ComplaintErrors;
}) {
  const { searchForm, updateSearchForm } = useAppState();
  const { items: categories, loading: catalogLoading, error: catalogError, refresh } = useCatalog();
  const [errors, setErrors] = useState<ComplaintErrors>({});

  const options = categoryOptions(categories, searchForm);

  useEffect(() => {
    if (catalogLoading || !categories.length) return;
    const reconciled = reconcileCategorySelection(categories, searchForm);
    if (!sameCategories(reconciled, searchForm)) {
      updateSearchForm(reconciled);
    }
  }, [categories, catalogLoading, searchForm, updateSearchForm]);

  function changeCategory(field: CategoryField, value: string) {
    updateSearchForm((current) => updateCategorySelection(categories, current, field, value));
  }

  function validate(): ComplaintErrors {
    const next: ComplaintErrors = {};
    if (!searchForm.product) next.product = 'Choose a product.';
    if (!searchForm.issue) next.issue = 'Choose an issue.';
    if (!searchForm.narrative.trim()) {
      next.narrative = 'Describe the complaint before searching.';
    } else if (textLength(searchForm.narrative) > NARRATIVE_LIMIT) {
      next.narrative = 'The narrative must be 50,000 characters or fewer.';
    }
    return next;
  }

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (searching) return;

    const nextErrors = validate();
    setErrors(nextErrors);
    const first = (Object.keys(FIELD_IDS) as ComplaintField[]).find((field) => nextErrors[field]);
    if (first) {
      document.getElementById(FIELD_IDS[first])?.focus();
      return;
    }
    onSearch({ ...searchForm });
  }

  const errorFor = (field: ComplaintField) => errors[field] ?? serverErrors[field];
  const catalogEmpty = !catalogLoading && !catalogError && categories.length === 0;
  const narrativeLength = textLength(searchForm.narrative);

  return (
    <section
      id={SEARCH_FORM_ANCHOR}
      className={styles.widget}
      aria-labelledby="search-widget-title"
    >
      <div className={styles.heading}>
        <h2 className={styles.title} id="search-widget-title">Describe the complaint</h2>
        <p className={styles.intro}>
          Product and issue are required. Sub-categories can stay unspecified.
        </p>
      </div>

      {catalogError ? (
        <StatusMessage tone="error" title={catalogError.title}>
          <p>{catalogError.message}</p>
          <Button variant="tertiary" onClick={() => void refresh()}>
            Retry categories
          </Button>
        </StatusMessage>
      ) : null}

      {catalogEmpty ? (
        <StatusMessage tone="info" title="No categories are available yet">
          <p>
            Categories come from the CFPB sample that loads when the API starts. Check
            that the backend is running with demo data enabled, then reload.
          </p>
        </StatusMessage>
      ) : null}

      <form id={SEARCH_FORM_ID} className={styles.form} onSubmit={handleSubmit} noValidate>
        <div className={styles.fieldGrid}>
          <Select
            id="search-product"
            label="Product"
            value={searchForm.product}
            onChange={(event) => changeCategory('product', event.target.value)}
            error={errorFor('product')}
            disabled={catalogLoading}
          >
            <option value="">Select a product</option>
            {options.products.map((product) => (
              <option key={product} value={product}>{product}</option>
            ))}
          </Select>

          <Select
            id="search-sub-product"
            label="Sub-product"
            optional
            value={searchForm.subProduct}
            onChange={(event) => changeCategory('subProduct', event.target.value)}
            error={errorFor('subProduct')}
            disabled={!searchForm.product || !options.subProducts.length}
            hint={!searchForm.product ? 'Choose a product first.' : undefined}
          >
            <option value="">Not specified</option>
            {options.subProducts.map((subProduct) => (
              <option key={subProduct} value={subProduct}>{subProduct}</option>
            ))}
          </Select>

          <Select
            id="search-issue"
            label="Issue"
            value={searchForm.issue}
            onChange={(event) => changeCategory('issue', event.target.value)}
            error={errorFor('issue')}
            disabled={!searchForm.product}
            hint={!searchForm.product ? 'Choose a product first.' : undefined}
          >
            <option value="">Select an issue</option>
            {options.issues.map((issue) => (
              <option key={issue} value={issue}>{issue}</option>
            ))}
          </Select>

          <Select
            id="search-sub-issue"
            label="Sub-issue"
            optional
            value={searchForm.subIssue}
            onChange={(event) => changeCategory('subIssue', event.target.value)}
            error={errorFor('subIssue')}
            disabled={!searchForm.product || !searchForm.issue || !options.subIssues.length}
            hint={!searchForm.issue ? 'Choose an issue first.' : undefined}
          >
            <option value="">Not specified</option>
            {options.subIssues.map((subIssue) => (
              <option key={subIssue} value={subIssue}>{subIssue}</option>
            ))}
          </Select>
        </div>

        <Textarea
          id="search-narrative"
          label="Complaint narrative"
          value={searchForm.narrative}
          onChange={(event) => updateSearchForm({ narrative: event.target.value })}
          error={errorFor('narrative')}
          hint={`${narrativeLength.toLocaleString('en-US')} / 50,000 characters. Write in English, like the CFPB narratives.`}
          rows={6}
          placeholder="What happened, what the consumer expected, and the key facts."
          required
        />

        <div className={styles.actions}>
          <Button type="submit" busy={searching} fullWidth>
            {searching ? 'Searching…' : 'Find precedents'}
          </Button>
          {catalogLoading ? <LoadingState label="Loading categories…" compact /> : null}
        </div>
      </form>
    </section>
  );
}
