import test from 'node:test';
import assert from 'node:assert/strict';
import { existsSync, readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import {
  CaseDetailFromJSON,
  CaseDetailToJSON,
  Configuration,
  ImportsApi,
  instanceOfAnalystCase,
  instanceOfCfpbCase,
  type AnalystCase,
  type CfpbCase,
  type CaseDetail,
} from '../src/index.js';

function findContractPath(): string {
  const here = path.dirname(fileURLToPath(import.meta.url));
  const candidates = [
    path.resolve(process.cwd(), 'docs/api/openapi.json'),
    path.resolve(here, '../../../../docs/api/openapi.json'),
    path.resolve(here, '../../../docs/api/openapi.json'),
  ];
  const found = candidates.find((candidate) => existsSync(candidate));
  if (!found) {
    throw new Error(`Could not locate docs/api/openapi.json from ${process.cwd()}`);
  }
  return found;
}

const contract = JSON.parse(readFileSync(findContractPath(), 'utf8'));
const cfpbExample: unknown =
  contract.paths['/cases/{id}'].get.responses['200'].content['application/json'].examples.cfpb.value;
const analystExample: unknown =
  contract.paths['/cases/{id}'].get.responses['200'].content['application/json'].examples.analyst.value;
const importExample: unknown =
  contract.paths['/imports/cfpb'].post.responses['201'].content['application/json'].examples.imported.value;

function own(json: Record<string, unknown>, key: string): boolean {
  return Object.prototype.hasOwnProperty.call(json, key);
}

function sourceKeyCount(serializedJson: string): number {
  return [...serializedJson.matchAll(/"source"\s*:/g)].length;
}

test('generated CfpbCase deserializes and serializes its contract example', () => {
  const detail: CaseDetail = CaseDetailFromJSON(cfpbExample);
  assert.equal(detail.source, 'CFPB');
  assert.ok(instanceOfCfpbCase(detail), 'expected the CFPB discriminator branch');

  const cfpb = detail as CfpbCase;
  assert.equal(cfpb.subProduct, 'Checking account');
  assert.equal(cfpb.subIssue, null);
  assert.equal(cfpb.receivedDate, '2024-11-12');
  assert.equal(cfpb.analystNotes, null);
  assert.deepEqual(cfpb.referenceCaseIds, []);

  const serialized = JSON.stringify(CaseDetailToJSON(detail));
  assert.equal(sourceKeyCount(serialized), 1);
  const json = JSON.parse(serialized) as Record<string, unknown>;
  assert.equal(json.source, 'CFPB');
  assert.ok(own(json, 'subProduct'));
  assert.equal(json.subProduct, 'Checking account');
  assert.ok(own(json, 'subIssue'));
  assert.equal(json.subIssue, null);
  assert.ok(own(json, 'analystNotes'));
  assert.equal(json.analystNotes, null);
  assert.ok(own(json, 'provenance'));
  assert.deepEqual(json.referenceCaseIds, []);
  assert.equal(json.receivedDate, '2024-11-12');
});

test('generated AnalystCase deserializes and serializes its contract example', () => {
  const detail: CaseDetail = CaseDetailFromJSON(analystExample);
  assert.equal(detail.source, 'ANALYST');
  assert.ok(instanceOfAnalystCase(detail), 'expected the ANALYST discriminator branch');

  const analyst = detail as AnalystCase;
  assert.equal(analyst.provenance, null);
  assert.equal(analyst.analystNotes.handlingNotes, 'Reviewed transfer evidence and requested a trace.');
  assert.ok(analyst.referenceCaseIds instanceof Set);
  assert.deepEqual(Array.from(analyst.referenceCaseIds), [
    '11111111-1111-4111-8111-111111111111',
  ]);

  const serialized = JSON.stringify(CaseDetailToJSON(detail));
  assert.equal(sourceKeyCount(serialized), 1);
  const json = JSON.parse(serialized) as Record<string, unknown>;
  assert.equal(json.source, 'ANALYST');
  assert.ok(own(json, 'provenance'));
  assert.equal(json.provenance, null);
  assert.ok(own(json, 'receivedDate'));
  assert.equal(json.receivedDate, null);
  assert.ok(own(json, 'subIssue'));
  assert.equal(json.subIssue, null);
  assert.ok(Array.isArray(json.referenceCaseIds));
  assert.deepEqual(json.referenceCaseIds, ['11111111-1111-4111-8111-111111111111']);
  assert.deepEqual(json.analystNotes, {
    handlingNotes: 'Reviewed transfer evidence and requested a trace.',
    outcomeAssessment: 'UNKNOWN',
    evaluationNotes: 'Awaiting confirmation from the consumer.',
  });
});

test('generated multipart import sends intact file bytes and a JSON metadata Blob', async () => {
  const bytes = Uint8Array.from([0x00, 0x01, 0x02, 0xff, 0x0d, 0x0a, 0x22, 0x2c]);
  const file = new Blob([bytes], { type: 'text/csv' });
  const metadata = {
    archiveUrl:
      'https://files.consumerfinance.gov/f/documents/CCDB_Export_8_November_2024_through_December_2024.zip',
    sourceFileName: 'finresolve-sample.csv',
    periodStart: '2024-11-01',
    periodEnd: '2024-12-31',
  };

  let captured: { url: string; init: RequestInit } | undefined;
  const fetchApi = async (url: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
    captured = { url: String(url), init: init ?? {} };
    return new Response(JSON.stringify(importExample), {
      status: 201,
      headers: { 'Content-Type': 'application/json' },
    });
  };

  const api = new ImportsApi(
    new Configuration({ basePath: 'http://localhost:8080/api', fetchApi }),
  );
  const report = await api.importCfpb({ file, metadata });

  assert.equal(report.batchId, '33333333-3333-4333-8333-333333333333');
  assert.equal(captured?.url, 'http://localhost:8080/api/imports/cfpb');
  assert.equal(captured?.init.method, 'POST');

  const body = captured?.init.body;
  assert.ok(body instanceof FormData, 'transport must build FormData');

  const filePart = body.get('file');
  assert.ok(filePart instanceof Blob, 'file part must remain binary');
  assert.deepEqual(new Uint8Array(await filePart.arrayBuffer()), bytes);

  const metadataPart = body.get('metadata');
  assert.ok(metadataPart instanceof Blob, 'metadata part must be a Blob');
  assert.equal(metadataPart.type, 'application/json');
  assert.deepEqual(JSON.parse(await metadataPart.text()), metadata);

  const headers = new Headers(captured?.init.headers);
  assert.equal(headers.has('content-type'), false, 'transport owns the multipart boundary');
});
