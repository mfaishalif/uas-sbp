import path from 'node:path';
import { fileURLToPath } from 'node:url';
import type { NextConfig } from 'next';

const projectRoot = fileURLToPath(new URL('../..', import.meta.url));

function apiOrigin(): string {
  const configured = process.env.FINRESOLVE_API_ORIGIN?.trim() || 'http://127.0.0.1:8080';
  const withoutTrailingSlash = configured.replace(/\/+$/, '');
  return withoutTrailingSlash.endsWith('/api')
    ? withoutTrailingSlash.slice(0, -4)
    : withoutTrailingSlash;
}

const nextConfig: NextConfig = {
  // Docker demo images opt into the standalone server; npm builds use next start.
  output: process.env.NEXT_STANDALONE === '1' ? 'standalone' : undefined,
  outputFileTracingRoot: path.resolve(projectRoot),
  reactStrictMode: true,
  experimental: {
    // /api requests pass through src/proxy.ts. Keep the full 21 MiB backend
    // multipart limit available instead of Next's 10 MB proxy default.
    proxyClientMaxBodySize: 22 * 1024 * 1024,
  },
  async rewrites() {
    return [
      {
        source: '/api/:path*',
        destination: `${apiOrigin()}/api/:path*`,
      },
    ];
  },
};

export default nextConfig;
