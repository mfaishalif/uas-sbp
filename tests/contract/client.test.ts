import test from 'node:test';
import assert from 'node:assert/strict';

import { ApiClientError, createClient } from '@finresolve/api-client';

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

const ERROR_BODY = {
  code: 'CASE_NOT_FOUND',
  message: 'Kasus tidak ditemukan.',
  fieldErrors: [{ field: 'id', code: 'NOT_FOUND', message: 'Tidak ada.' }],
  timestamp: '2026-10-26T10:00:00Z',
};

test('createClient targets /api once and returns generated tag APIs', async () => {
  const seen: string[] = [];
  const client = createClient('http://example.test/', async (input) => {
    seen.push(String(input));
    return jsonResponse(200, { status: 'UP', database: 'UP' });
  });

  const health = await client.system.getHealth();

  assert.equal(health.status, 'UP');
  assert.deepEqual(seen, ['http://example.test/api/health']);
  assert.equal(client.configuration.basePath, 'http://example.test/api');
});

test('generated ApiError becomes ApiClientError with status, code and field errors', async () => {
  const client = createClient('http://example.test', async () =>
    jsonResponse(404, ERROR_BODY),
  );

  await assert.rejects(
    client.cases.getCase({ id: '00000000-0000-4000-8000-000000000000' }),
    (error: unknown) => {
      assert.ok(error instanceof ApiClientError);
      assert.equal(error.name, 'ApiClientError');
      assert.equal(error.status, 404);
      assert.equal(error.code, 'CASE_NOT_FOUND');
      assert.deepEqual(error.fieldErrors, ERROR_BODY.fieldErrors);
      return true;
    },
  );
});

test('non-JSON failure and network failure use TRANSPORT_ERROR', async () => {
  const proxyFailure = createClient('http://example.test', async () =>
    new Response('<html>bad gateway</html>', {
      status: 502,
      headers: { 'Content-Type': 'text/html' },
    }),
  );
  await assert.rejects(proxyFailure.system.getHealth(), (error: unknown) => {
    assert.ok(error instanceof ApiClientError);
    assert.equal(error.status, 502);
    assert.equal(error.code, 'TRANSPORT_ERROR');
    return true;
  });

  const offline = createClient('http://example.test', async () => {
    throw new TypeError('connection refused');
  });
  await assert.rejects(offline.system.getHealth(), (error: unknown) => {
    assert.ok(error instanceof ApiClientError);
    assert.equal(error.status, 0);
    assert.equal(error.code, 'TRANSPORT_ERROR');
    return true;
  });
});

test('mutations are never retried implicitly', async () => {
  let calls = 0;
  const client = createClient('http://example.test', async () => {
    calls += 1;
    return jsonResponse(500, {
      code: 'INTERNAL_ERROR',
      message: 'Terjadi kesalahan internal.',
      fieldErrors: [],
      timestamp: '2026-10-26T10:00:00Z',
    });
  });

  await assert.rejects(
    client.cases.retainCase({
      retainCaseRequest: {
        product: 'Checking or savings account',
        issue: 'Managing an account',
        narrative: 'A missing transfer narrative.',
        handlingNotes: 'Reviewed.',
        outcomeAssessment: 'UNKNOWN',
      },
    }),
    (error: unknown) => {
      assert.ok(error instanceof ApiClientError);
      assert.equal(error.code, 'INTERNAL_ERROR');
      return true;
    },
  );
  assert.equal(calls, 1);
});
