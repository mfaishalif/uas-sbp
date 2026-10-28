'use client';

import { useEffect, useRef, useState, type FormEvent } from 'react';
import { useRouter } from 'next/navigation';
import type { SearchMatch } from '@finresolve/api-client';
import { EmptyState } from '@/components/ui/EmptyState';
import { Button } from '@/components/ui/Button';
import { Card } from '@/components/ui/Card';
import { LoadingState } from '@/components/ui/LoadingState';
import { Select, Textarea } from '@/components/ui/FormField';
import { StatusMessage } from '@/components/ui/StatusMessage';
import { LinkButton } from '@/components/ui/LinkButton';
import { SearchResults } from '@/components/search/SearchResults';
import { useAppState } from '@/contexts/AppState';
import { useCatalog } from '@/contexts/CatalogContext';
import { getApiClient } from '@/lib/api';
import {
  categoryOptions,
  reconcileCategorySelection,
  updateCategorySelection,
  type CategoryField,
} from '@/lib/categories';
import { toUiError, type UiError } from '@/lib/errors';
import { textLength } from '@/lib/format';
import styles from './FindPrecedentsPage.module.css';

type SearchErrors = Partial<Record<'product' | 'issue' | 'narrative', string>>;

function sameComplaint(
  left: { product: string; subProduct: string; issue: string; subIssue: string },
  right: { product: string; subProduct: string; issue: string; subIssue: string },
): boolean {
  return left.product === right.product
    && left.subProduct === right.subProduct
    && left.issue === right.issue
    && left.subIssue === right.subIssue;
}

export function FindPrecedentsPage() {
  const router = useRouter();
  const {
    searchForm,
    updateSearchForm,
    searchResult,
    setSearchResult,
    selectedReferenceIds,
    toggleSearchReference,
    seedRetentionFromSearch,
    cacheReference,
  } = useAppState();
  const { items: categories, loading: catalogLoading, error: catalogError, refresh } = useCatalog();
  const [searching, setSearching] = useState(false);
  const [errors, setErrors] = useState<SearchErrors>({});
  const [searchError, setSearchError] = useState<UiError | null>(null);
  const inFlight = useRef(false);
  const mounted = useRef(true);

  const options = categoryOptions(categories, searchForm);

  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
    };
  }, []);

  useEffect(() => {
    if (catalogLoading || !categories.length) return;
    const reconciled = reconcileCategorySelection(categories, searchForm);
    if (!sameComplaint(reconciled, searchForm)) {
      updateSearchForm(reconciled);
    }
  }, [categories, catalogLoading, searchForm, updateSearchForm]);

  function changeCategory(field: CategoryField, value: string) {
    updateSearchForm((current) => updateCategorySelection(categories, current, field, value));
  }

  function validate(): SearchErrors {
    const next: SearchErrors = {};
    if (!searchForm.product) next.product = 'Choose a product.';
    if (!searchForm.issue) next.issue = 'Choose an issue.';
    if (!searchForm.narrative.trim()) {
      next.narrative = 'Describe the complaint before searching.';
    } else if (textLength(searchForm.narrative) > 50_000) {
      next.narrative = 'The narrative must be 50,000 characters or fewer.';
    }
    if (!searchForm.subProduct && options.subProducts.length) {
      // Sub-products are optional; no error for an omitted value.
    }
    if (!searchForm.subIssue && options.subIssues.length) {
      // Sub-issues are optional; no error for an omitted value.
    }
    return next;
  }

  async function handleSearch(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (inFlight.current) return;

    const nextErrors = validate();
    setErrors(nextErrors);
    if (Object.keys(nextErrors).length) {
      const first = nextErrors.product
        ? 'search-product'
        : nextErrors.issue
          ? 'search-issue'
          : 'search-narrative';
      document.getElementById(first)?.focus();
      return;
    }

    inFlight.current = true;
    setSearching(true);
    setSearchError(null);
    setSearchResult(null);

    try {
      const result = await getApiClient().search.searchCases({
        searchRequest: {
          product: searchForm.product,
          subProduct: searchForm.subProduct || null,
          issue: searchForm.issue,
          subIssue: searchForm.subIssue || null,
          narrative: searchForm.narrative,
          limit: 5,
        },
      });
      if (mounted.current) setSearchResult(result);
    } catch (caught) {
      if (mounted.current) setSearchError(toUiError(caught));
    } finally {
      inFlight.current = false;
      if (mounted.current) setSearching(false);
    }
  }

  function toggleReference(match: SearchMatch) {
    cacheReference(match._case);
    toggleSearchReference(match._case.id);
  }

  function continueToRetention() {
    seedRetentionFromSearch();
    router.push('/cases/new');
  }

  const catalogEmpty = !catalogLoading && !catalogError && categories.length === 0;

  return (
    <div className={`fin-container ${styles.page}`}>
      <header className={styles.hero}>
        <p className="page-eyebrow">Case-based reasoning workspace</p>
        <h1 className={styles.title}>Find context for your next complaint</h1>
        <p className={styles.lead}>
          Describe the complaint and FinResolve will retrieve historical precedents,
          show why they matched, and keep every score explainable.
        </p>
      </header>

      <div className={styles.layout} data-testid="search-layout">
        <Card as="section" className={styles.formCard}>
          <div className={styles.formHeading}>
            <p className="page-eyebrow">Complaint input</p>
            <h2 className={styles.formTitle}>Search precedent cases</h2>
            <p className={styles.formIntro}>
              Categories come from the imported CFPB sample. Only product and issue
              are required; sub-categories can stay unspecified.
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
                Import a CFPB CSV to build the category catalog before searching.
              </p>
              <LinkButton href="/imports" variant="tertiary">
                Import data
              </LinkButton>
            </StatusMessage>
          ) : null}

          <form className={styles.form} onSubmit={handleSearch} noValidate>
            <div className={styles.fieldGrid}>
              <Select
                id="search-product"
                label="Product"
                value={searchForm.product}
                onChange={(event) => changeCategory('product', event.target.value)}
                error={errors.product}
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
                error={errors.issue}
                disabled={!searchForm.product}
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
              error={errors.narrative}
              hint={`${textLength(searchForm.narrative).toLocaleString('en-US')} / 50,000 characters. Use the same language as the source narrative.`}
              maxLength={50_000}
              rows={7}
              placeholder="Describe what happened, what the consumer expected, and the key facts of the complaint."
              required
            />

            <div className={styles.formActions}>
              <Button type="submit" busy={searching} fullWidth>
                {searching ? 'Searching…' : 'Find precedents'}
              </Button>
              {catalogLoading ? <LoadingState label="Loading categories…" compact /> : null}
            </div>
          </form>
        </Card>

        <section className={styles.resultsColumn} aria-label="Precedent results">
          {searchError ? (
            <StatusMessage tone="error" title={searchError.title}>
              <p>{searchError.message}</p>
              {searchError.fieldErrors.length ? (
                <ul className={styles.errorList}>
                  {searchError.fieldErrors.map((fieldError) => (
                    <li key={`${fieldError.field}-${fieldError.code}`}>
                      <strong>{fieldError.field}:</strong> {fieldError.message}
                    </li>
                  ))}
                </ul>
              ) : null}
            </StatusMessage>
          ) : null}

          {searching ? <LoadingState label="Searching the case knowledge base…" /> : null}

          {!searching && searchResult ? (
            <SearchResults
              response={searchResult}
              selectedReferenceIds={selectedReferenceIds}
              onToggleReference={toggleReference}
              onContinue={continueToRetention}
            />
          ) : null}

          {!searching && !searchResult && !searchError ? (
            <Card className={styles.startCard}>
              <EmptyState title="Start with a complaint">
                <p>
                  Choose a product and issue, then add the complaint narrative. Results
                  will explain category and text similarity, shared terms, and the
                  server configuration that produced them.
                </p>
                <p>
                  You can select any results as references and carry the complaint into
                  an analyst retention form.
                </p>
              </EmptyState>
            </Card>
          ) : null}
        </section>
      </div>
    </div>
  );
}
