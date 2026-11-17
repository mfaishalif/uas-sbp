'use client';

import Link from 'next/link';
import { useEffect, useRef, useState } from 'react';
import type { CaseDetail } from '@finresolve/api-client';
import { CaseDetailModal } from '@/components/cases/CaseDetailModal';
import { LinkButton } from '@/components/ui/LinkButton';
import { LoadingState } from '@/components/ui/LoadingState';
import { StatusMessage } from '@/components/ui/StatusMessage';
import { useAppState } from '@/contexts/AppState';
import { getApiClient } from '@/lib/api';
import { categoryPath, sameComplaint, type ComplaintFormValues } from '@/lib/case';
import { errorForField, toUiError, type UiError } from '@/lib/errors';
import { SEARCH_LIMIT } from '@/lib/scoring';
import { FocusFormLink } from './FocusFormLink';
import { PrecedentResults } from './PrecedentResults';
import { RecommendationPanel } from './RecommendationPanel';
import { SEARCH_FORM_ID, SearchForm, type ComplaintErrors, type ComplaintField } from './SearchForm';
import styles from './PrecedentWorkspace.module.css';

const COMPLAINT_FIELDS: ComplaintField[] = ['product', 'subProduct', 'issue', 'subIssue', 'narrative'];

/**
 * Sections 1–3 of the landing page: the hero with the search widget, the
 * precedent results, and the recommendation + save panel. They share one
 * search request, so they live in one client component.
 */
export function PrecedentWorkspace() {
  const { searchForm, resetSearchForm, lastSearch, setLastSearch } = useAppState();
  const [searching, setSearching] = useState(false);
  const [searchError, setSearchError] = useState<UiError | null>(null);
  const [serverErrors, setServerErrors] = useState<ComplaintErrors>({});
  const [saved, setSaved] = useState<CaseDetail | null>(null);
  const [openCaseId, setOpenCaseId] = useState<string | null>(null);
  const [searchCount, setSearchCount] = useState(0);
  const inFlight = useRef(false);
  const mounted = useRef(true);
  const savedRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
    };
  }, []);

  useEffect(() => {
    if (saved) savedRef.current?.focus();
  }, [saved]);

  async function runSearch(query: ComplaintFormValues) {
    if (inFlight.current) return;
    inFlight.current = true;
    setSearching(true);
    setSearchError(null);
    setServerErrors({});
    setSaved(null);
    setLastSearch(null);

    try {
      const response = await getApiClient().search.searchCases({
        searchRequest: {
          product: query.product,
          subProduct: query.subProduct || null,
          issue: query.issue,
          subIssue: query.subIssue || null,
          narrative: query.narrative,
          limit: SEARCH_LIMIT,
        },
      });
      if (!mounted.current) return;
      setLastSearch({ query, response });
      setSearchCount((count) => count + 1);
    } catch (caught) {
      if (!mounted.current) return;
      const uiError = toUiError(caught);
      setSearchError(uiError);
      const fieldErrors: ComplaintErrors = {};
      for (const field of COMPLAINT_FIELDS) {
        const message = errorForField(uiError, field);
        if (message) fieldErrors[field] = message;
      }
      setServerErrors(fieldErrors);
    } finally {
      inFlight.current = false;
      if (mounted.current) setSearching(false);
    }
  }

  function handleSaved(detail: CaseDetail) {
    setSaved(detail);
    setLastSearch(null);
    setServerErrors({});
    resetSearchForm();
  }

  function searchAgain() {
    const form = document.getElementById(SEARCH_FORM_ID);
    if (form instanceof HTMLFormElement) form.requestSubmit();
  }

  const formChanged = lastSearch ? !sameComplaint(searchForm, lastSearch.query) : false;
  const savedReferences = saved ? Array.from(saved.referenceCaseIds).length : 0;
  const announcement = searching
    ? ''
    : lastSearch
      ? lastSearch.response.status === 'MATCHES'
        ? `${lastSearch.response.returnedCount} precedents found.`
        : 'No precedent cleared the threshold.'
      : '';

  return (
    <>
      <section className={styles.hero} aria-labelledby="hero-title">
        <div className={`fin-container ${styles.heroGrid}`}>
          <div className={styles.heroCopy}>
            <p className="page-eyebrow">Case-based reasoning for complaint analysts</p>
            <h1 className={styles.heroTitle} id="hero-title">
              Answer the next complaint with the cases behind it
            </h1>
            <p className={styles.heroLead}>
              Describe a consumer complaint. FinResolve finds the three most similar
              past cases, shows exactly why each one matched, and suggests a response
              weighted by that similarity.
            </p>
            <div className={styles.heroActions}>
              <FocusFormLink>Find precedents</FocusFormLink>
              <LinkButton href="/cases" variant="tertiary">Browse case library</LinkButton>
            </div>
            <ul className={styles.heroFacts}>
              <li><strong>500</strong> CFPB complaints loaded</li>
              <li><strong>Top 3</strong> precedents per search</li>
              <li><strong>Every score</strong> explained</li>
            </ul>
          </div>
          <SearchForm searching={searching} onSearch={runSearch} serverErrors={serverErrors} />
        </div>
      </section>

      <section
        id="precedents"
        className={styles.results}
        aria-labelledby="results-title"
        aria-busy={searching}
      >
        <div className={`fin-container ${styles.resultsInner}`}>
          <div className={styles.resultsHeading}>
            <p className="page-eyebrow">Retrieve</p>
            <h2 className={styles.sectionTitle} id="results-title">Closest precedents</h2>
            {lastSearch && !searching ? (
              <p className={styles.searched}>
                For <strong>{categoryPath(lastSearch.query)}</strong>
              </p>
            ) : null}
          </div>

          <p className="visually-hidden" role="status">{announcement}</p>

          <div className={styles.live}>
            {saved ? (
              <div ref={savedRef} tabIndex={-1} className={styles.savedFocus}>
                <StatusMessage tone="success" title="Case saved to the library">
                  <p>
                    The {saved.product} case was saved as an Analyst case with{' '}
                    {savedReferences}{' '}
                    {savedReferences === 1 ? 'reference' : 'references'}. It is now a
                    candidate for future searches.
                  </p>
                  <p>
                    <Link href="/cases">Open the case library</Link>
                  </p>
                </StatusMessage>
              </div>
            ) : null}

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

            {searching ? <LoadingState label="Searching the case library…" /> : null}

            {!searching && lastSearch ? (
              <PrecedentResults response={lastSearch.response} onOpenCase={setOpenCaseId} />
            ) : null}
          </div>

          {!searching && !lastSearch && !searchError && !saved ? (
            <div className={styles.placeholder}>
              <p>
                Your three closest precedents appear here, each with its total score, the
                category and text breakdown, shared terms, and the response the company
                gave.
              </p>
              <FocusFormLink variant="tertiary">Start with the complaint form</FocusFormLink>
            </div>
          ) : null}

          {!searching && lastSearch ? (
            <RecommendationPanel
              key={searchCount}
              response={lastSearch.response}
              complaint={searchForm}
              formChanged={formChanged}
              onSearchAgain={searchAgain}
              onSaved={handleSaved}
              onComplaintErrors={setServerErrors}
            />
          ) : null}
        </div>
      </section>

      <CaseDetailModal caseId={openCaseId} onClose={() => setOpenCaseId(null)} />
    </>
  );
}
