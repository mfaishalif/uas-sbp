import { NextResponse, type NextRequest } from 'next/server';

/**
 * Requests reaching this origin are already same-origin browser requests. The
 * backend CORS contract still checks Origin, so the proxy removes it before the
 * Next.js rewrite forwards the request. Direct API clients keep sending Origin
 * and remain subject to the backend's exact-origin allowlist.
 */
export function proxy(request: NextRequest) {
  const headers = new Headers(request.headers);
  headers.delete('origin');
  return NextResponse.next({ request: { headers } });
}

export const config = {
  matcher: '/api/:path*',
};
