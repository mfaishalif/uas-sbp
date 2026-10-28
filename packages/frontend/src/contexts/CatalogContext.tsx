'use client';

import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from 'react';
import type { CategoryTuple } from '@finresolve/api-client';
import { getApiClient } from '@/lib/api';
import { toUiError, type UiError } from '@/lib/errors';

interface CatalogValue {
  items: CategoryTuple[];
  loading: boolean;
  error: UiError | null;
  refresh: () => Promise<void>;
}

const CatalogContext = createContext<CatalogValue | null>(null);

export function CatalogProvider({ children }: { children: ReactNode }) {
  const [items, setItems] = useState<CategoryTuple[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<UiError | null>(null);
  const requestId = useRef(0);

  const refresh = useCallback(async () => {
    const current = ++requestId.current;
    setLoading(true);
    try {
      const categories = await getApiClient().categories.getCategories();
      if (requestId.current !== current) return;
      setItems(categories.items);
      setError(null);
    } catch (caught) {
      if (requestId.current !== current) return;
      setError(toUiError(caught));
    } finally {
      if (requestId.current === current) setLoading(false);
    }
  }, []);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  const value = useMemo<CatalogValue>(
    () => ({ items, loading, error, refresh }),
    [items, loading, error, refresh],
  );

  return <CatalogContext.Provider value={value}>{children}</CatalogContext.Provider>;
}

export function useCatalog(): CatalogValue {
  const context = useContext(CatalogContext);
  if (!context) {
    throw new Error('useCatalog must be used within CatalogProvider');
  }
  return context;
}
