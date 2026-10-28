'use client';

import {
  createContext,
  useCallback,
  useContext,
  useMemo,
  useState,
  type ReactNode,
} from 'react';
import type { CaseDetail, CaseSummary, SearchResponse } from '@finresolve/api-client';
import {
  EMPTY_COMPLAINT_FORM,
  emptyCaseForm,
  type CaseFormValues,
  type ComplaintFormValues,
  type DraftUpdater,
} from '@/lib/case';

function applyUpdate<T>(current: T, update: DraftUpdater<T>): T {
  return typeof update === 'function'
    ? (update as (value: T) => T)(current)
    : { ...current, ...update };
}

function mergeReferenceIds(target: Set<string>, incoming: Iterable<string>): Set<string> {
  const merged = new Set(target);
  const seen = new Set(Array.from(merged, (id) => id.toLowerCase()));
  for (const id of incoming) {
    if (merged.size >= 20) break;
    if (seen.has(id.toLowerCase())) continue;
    seen.add(id.toLowerCase());
    merged.add(id);
  }
  return merged;
}

interface AppStateValue {
  searchForm: ComplaintFormValues;
  updateSearchForm: (update: DraftUpdater<ComplaintFormValues>) => void;
  searchResult: SearchResponse | null;
  setSearchResult: (result: SearchResponse | null) => void;
  selectedReferenceIds: Set<string>;
  toggleSearchReference: (id: string) => void;
  setSelectedReferenceIds: (ids: Set<string>) => void;
  clearSearchSelection: () => void;
  retainDraft: CaseFormValues;
  updateRetainDraft: (update: DraftUpdater<CaseFormValues>) => void;
  resetRetainDraft: () => void;
  seedRetentionFromSearch: () => void;
  editDrafts: Record<string, CaseFormValues>;
  updateEditDraft: (id: string, update: DraftUpdater<CaseFormValues>) => void;
  clearEditDraft: (id: string) => void;
  referenceCache: Record<string, CaseSummary>;
  cacheReference: (reference: CaseSummary | CaseDetail) => void;
  dataVersion: number;
  notifyDataChanged: () => void;
}

const AppStateContext = createContext<AppStateValue | null>(null);

function asSummary(reference: CaseSummary | CaseDetail): CaseSummary {
  if ('provenance' in reference || 'analystNotes' in reference) {
    return {
      id: reference.id,
      source: reference.source,
      product: reference.product,
      subProduct: reference.subProduct,
      issue: reference.issue,
      subIssue: reference.subIssue,
      receivedDate: reference.receivedDate,
      company: reference.company,
      companyResponse: reference.companyResponse,
      createdAt: reference.createdAt,
      updatedAt: reference.updatedAt,
    };
  }
  return reference;
}

export function AppStateProvider({ children }: { children: ReactNode }) {
  const [searchForm, setSearchForm] = useState<ComplaintFormValues>({
    ...EMPTY_COMPLAINT_FORM,
  });
  const [searchResult, setSearchResultState] = useState<SearchResponse | null>(null);
  const [selectedReferenceIds, setSelectedReferenceIdsState] = useState<Set<string>>(
    () => new Set(),
  );
  const [retainDraft, setRetainDraft] = useState<CaseFormValues>(emptyCaseForm);
  const [editDrafts, setEditDrafts] = useState<Record<string, CaseFormValues>>({});
  const [referenceCache, setReferenceCache] = useState<Record<string, CaseSummary>>({});
  const [dataVersion, setDataVersion] = useState(0);

  const updateSearchForm = useCallback((update: DraftUpdater<ComplaintFormValues>) => {
    setSearchForm((current) => applyUpdate(current, update));
  }, []);

  const setSearchResult = useCallback((result: SearchResponse | null) => {
    setSearchResultState(result);
    if (!result || result.status !== 'MATCHES') {
      setSelectedReferenceIdsState(new Set());
      return;
    }
    const valid = new Set(result.results.map((match) => match._case.id.toLowerCase()));
    setSelectedReferenceIdsState((current) => {
      const next = new Set(
        Array.from(current).filter((id) => valid.has(id.toLowerCase())),
      );
      return next.size === current.size ? current : next;
    });
  }, []);

  const toggleSearchReference = useCallback((id: string) => {
    setSelectedReferenceIdsState((current) => {
      const normalized = id.toLowerCase();
      const exists = Array.from(current).some((value) => value.toLowerCase() === normalized);
      if (exists) {
        return new Set(
          Array.from(current).filter((value) => value.toLowerCase() !== normalized),
        );
      }
      if (current.size >= 20) return current;
      return new Set([...current, id]);
    });
  }, []);

  const setSelectedReferenceIds = useCallback((ids: Set<string>) => {
    setSelectedReferenceIdsState(new Set(ids));
  }, []);

  const clearSearchSelection = useCallback(() => {
    setSelectedReferenceIdsState(new Set());
  }, []);

  const updateRetainDraft = useCallback((update: DraftUpdater<CaseFormValues>) => {
    setRetainDraft((current) => applyUpdate(current, update));
  }, []);

  const resetRetainDraft = useCallback(() => {
    setRetainDraft(emptyCaseForm());
  }, []);

  const seedRetentionFromSearch = useCallback(() => {
    setRetainDraft((current) => ({
      ...current,
      ...searchForm,
      referenceCaseIds: mergeReferenceIds(current.referenceCaseIds, selectedReferenceIds),
    }));
  }, [searchForm, selectedReferenceIds]);

  const updateEditDraft = useCallback((id: string, update: DraftUpdater<CaseFormValues>) => {
    setEditDrafts((current) => ({
      ...current,
      [id]: applyUpdate(current[id] ?? emptyCaseForm(), update),
    }));
  }, []);

  const clearEditDraft = useCallback((id: string) => {
    setEditDrafts((current) => {
      if (!(id in current)) return current;
      const next = { ...current };
      delete next[id];
      return next;
    });
  }, []);

  const cacheReference = useCallback((reference: CaseSummary | CaseDetail) => {
    const summary = asSummary(reference);
    setReferenceCache((current) => ({ ...current, [summary.id]: summary }));
  }, []);

  const notifyDataChanged = useCallback(() => {
    setDataVersion((current) => current + 1);
  }, []);

  const value = useMemo<AppStateValue>(
    () => ({
      searchForm,
      updateSearchForm,
      searchResult,
      setSearchResult,
      selectedReferenceIds,
      toggleSearchReference,
      setSelectedReferenceIds,
      clearSearchSelection,
      retainDraft,
      updateRetainDraft,
      resetRetainDraft,
      seedRetentionFromSearch,
      editDrafts,
      updateEditDraft,
      clearEditDraft,
      referenceCache,
      cacheReference,
      dataVersion,
      notifyDataChanged,
    }),
    [
      searchForm,
      updateSearchForm,
      searchResult,
      setSearchResult,
      selectedReferenceIds,
      toggleSearchReference,
      setSelectedReferenceIds,
      clearSearchSelection,
      retainDraft,
      updateRetainDraft,
      resetRetainDraft,
      seedRetentionFromSearch,
      editDrafts,
      updateEditDraft,
      clearEditDraft,
      referenceCache,
      cacheReference,
      dataVersion,
      notifyDataChanged,
    ],
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
