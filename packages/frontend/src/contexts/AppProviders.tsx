import type { ReactNode } from 'react';
import { AppStateProvider } from './AppState';
import { CatalogProvider } from './CatalogContext';

export function AppProviders({ children }: { children: ReactNode }) {
  return (
    <AppStateProvider>
      <CatalogProvider>{children}</CatalogProvider>
    </AppStateProvider>
  );
}
