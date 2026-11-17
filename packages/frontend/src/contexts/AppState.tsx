'use client';

import {
  createContext,
  useCallback,
  useContext,
  useMemo,
  useState,
  type ReactNode,
} from 'react';
import type { SearchConfiguration, SearchResponse } from '@finresolve/api-client';
import {
  EMPTY_COMPLAINT_FORM,
  type ComplaintFormValues,
  type DraftUpdater,
} from '@/lib/case';

function applyUpdate<T>(current: T, update: DraftUpdater<T>): T {
  return typeof update === 'function'
    ? (update as (value: T) => T)(current)
    : { ...current, ...update };
}

/** The complaint exactly as it was searched, and what the server returned for it. */
export interface LastSearch {
  query: ComplaintFormValues;
  response: SearchResponse;
}

interface AppStateValue {
  searchForm: ComplaintFormValues;
  updateSearchForm: (update: DraftUpdater<ComplaintFormValues>) => void;
  resetSearchForm: () => void;
  lastSearch: LastSearch | null;
  setLastSearch: (search: LastSearch | null) => void;
  /** Configuration from the most recent successful search; survives a reset. */
  searchConfiguration: SearchConfiguration | null;
}

const AppStateContext = createContext<AppStateValue | null>(null);

/**
 * In-memory state shared across client navigation. Nothing is written to
 * storage or the URL, so complaint text never leaves the page.
 */
export function AppStateProvider({ children }: { children: ReactNode }) {
  const [searchForm, setSearchForm] = useState<ComplaintFormValues>({
    ...EMPTY_COMPLAINT_FORM,
  });
  const [lastSearch, setLastSearchState] = useState<LastSearch | null>(null);
  const [searchConfiguration, setSearchConfiguration] = useState<SearchConfiguration | null>(null);

  const updateSearchForm = useCallback((update: DraftUpdater<ComplaintFormValues>) => {
    setSearchForm((current) => applyUpdate(current, update));
  }, []);

  const resetSearchForm = useCallback(() => {
    setSearchForm({ ...EMPTY_COMPLAINT_FORM });
  }, []);

  const setLastSearch = useCallback((search: LastSearch | null) => {
    setLastSearchState(search);
    if (search) setSearchConfiguration(search.response._configuration);
  }, []);

  const value = useMemo<AppStateValue>(
    () => ({
      searchForm,
      updateSearchForm,
      resetSearchForm,
      lastSearch,
      setLastSearch,
      searchConfiguration,
    }),
    [searchForm, updateSearchForm, resetSearchForm, lastSearch, setLastSearch, searchConfiguration],
  );

  return <AppStateContext.Provider value={value}>{children}</AppStateContext.Provider>;
}

export function useAppState(): AppStateValue {
  const context = useContext(AppStateContext);
  if (!context) {
    throw new Error('useAppState must be used within AppStateProvider');
  }
  return context;
}
