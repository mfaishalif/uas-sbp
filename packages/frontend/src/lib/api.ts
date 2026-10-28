import { createClient, type FinResolveClient } from '@finresolve/api-client';

let client: FinResolveClient | undefined;

/**
 * The browser always talks to the frontend origin. Next.js rewrites /api to the
 * configured backend, so no backend origin or CORS configuration leaks into UI code.
 */
export function getApiClient(): FinResolveClient {
  client ??= createClient('');
  return client;
}
