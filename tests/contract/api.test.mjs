import test from 'node:test';
import assert from 'node:assert/strict';
import { DatabaseSync } from 'node:sqlite';

import { startTestServer } from '../../tools/test-server.mjs';
import { createClient, ApiClientError } from '@finresolve/api-client';
import { assertErrorSchema, assertResponse } from './schema.mjs';

const CHECKING = {
    product: 'Checking or savings account',
    subProduct: 'Checking account',
    issue: 'Managing an account',
    subIssue: null,
};
const CSV_HEADER =
    'Complaint ID,Product,Sub-product,Issue,Sub-issue,'
    + 'Consumer complaint narrative,Company,Company response to consumer,Date received';
const METADATA = {
    archiveUrl: 'https://files.consumerfinance.gov/f/documents/synthetic-live-contract.zip',
    sourceFileName: 'finresolve-live-fixture.csv',
    periodStart: '2024-11-01',
    periodEnd: '2024-12-31',
};
const WARNING =
    'Kemiripan dan respons historis bukan bukti keberhasilan penanganan. '
    + 'Bobot serta ambang awal belum dikalibrasi.';

function csvRow(complaintId, narrative) {
    return [
        complaintId,
        CHECKING.product,
        CHECKING.subProduct,
        CHECKING.issue,
        '',
        `"${narrative.replaceAll('"', '""')}"`,
        'Example Bank',
        'Closed with explanation',
        '2024-11-12',
    ].join(',') + '\n';
}

function csvBytes(rows) {
    return new Blob([CSV_HEADER + '\n' + rows.join('')], { type: 'text/csv' });
}

function retainRequest(overrides = {}) {
    return {
        product: CHECKING.product,
        subProduct: CHECKING.subProduct,
        issue: CHECKING.issue,
        subIssue: null,
        narrative: 'My transfer is missing from my checking account.',
        receivedDate: '2024-12-01',
        company: 'Example Bank',
        companyResponse: 'Closed with explanation',
        handlingNotes: 'Reviewed transfer evidence and requested a trace.',
        outcomeAssessment: 'UNKNOWN',
        evaluationNotes: 'Awaiting confirmation from the consumer.',
        referenceCaseIds: new Set(),
        ...overrides,
    };
}

function rawRetainBody(overrides = {}) {
    const request = retainRequest({ referenceCaseIds: [], ...overrides });
    return JSON.stringify(request);
}

async function validateRaw(operationId, rawPromise) {
    const raw = await rawPromise;
    const response = raw.raw;
    const body = await response.clone().json();
    assertResponse(operationId, response.status, response.headers, body);
    return { response, body, value: await raw.value() };
}

async function fetchJson(baseUrl, path, init) {
    const response = await fetch(`${baseUrl}${path}`, init);
    const text = await response.text();
    const body = text ? JSON.parse(text) : undefined;
    return { response, body };
}

test('live contract, generated client, retention, search, and controlled failures', async () => {
    const server = await startTestServer();
    const client = createClient(server.baseUrl);

    try {
        const health = await validateRaw('getHealth', client.system.getHealthRaw());
        assert.equal(health.body.status, 'UP');
        assert.equal(health.body.database, 'UP');

        const emptySearch = await validateRaw(
            'searchCases',
            client.search.searchCasesRaw({
                searchRequest: {
                    product: 'Unknown product',
                    issue: 'Unknown issue',
                    narrative: 'A missing transfer narrative.',
                },
            }),
        );
        assert.equal(emptySearch.body.status, 'EMPTY_KNOWLEDGE_BASE');
        assert.equal(emptySearch.body.corpusSize, 0);
        assert.deepEqual(emptySearch.body.results, []);
        assert.equal(emptySearch.body.returnedCount, 0);
        assert.equal(emptySearch.body.limit, 5);
        assert.deepEqual(emptySearch.body.warnings, [WARNING]);

        const emptyCategories = await validateRaw(
            'getCategories',
            client.categories.getCategoriesRaw(),
        );
        assert.equal(emptyCategories.body.scope, 'IMPORTED_CFPB_SAMPLE');
        assert.deepEqual(emptyCategories.body.items, []);

        const emptyList = await validateRaw(
            'listCases',
            client.cases.listCasesRaw({ page: 0, size: 20 }),
        );
        assert.equal(emptyList.body.totalElements, 0);
        assert.deepEqual(emptyList.body.items, []);

        const csv = csvBytes([
            csvRow('9007199254740993', 'My transfer is missing from my checking account.'),
            csvRow('990000002', 'A transfer is still missing after I called support.'),
        ]);
        const imported = await validateRaw(
            'importCfpb',
            client.imports.importCfpbRaw({
                file: csv,
                metadata: METADATA,
            }),
        );
        assert.equal(imported.response.status, 201);
        assert.equal(imported.body.replayed, false);
        assert.equal(imported.body.processed, 2);
        assert.equal(imported.body.imported, 2);
        assert.equal(imported.body.duplicates, 0);
        assert.equal(imported.body.rejected, 0);
        assert.equal(typeof imported.body.importedAt, 'string');
        assert.match(imported.body.importedAt, /Z$/);
        assert.equal(imported.body.metadata.sourceFileName, METADATA.sourceFileName);
        assert.match(imported.body.csvSha256, /^[a-f0-9]{64}$/);
        const batchId = imported.body.batchId;

        const replay = await validateRaw(
            'importCfpb',
            client.imports.importCfpbRaw({
                file: csv,
                metadata: {
                    ...METADATA,
                    archiveUrl: `  ${METADATA.archiveUrl}  `,
                    sourceFileName: `  ${METADATA.sourceFileName}  `,
                },
            }),
        );
        assert.equal(replay.response.status, 200);
        assert.equal(replay.body.replayed, true);
        assert.equal(replay.body.batchId, batchId);

        const categories = await validateRaw(
            'getCategories',
            client.categories.getCategoriesRaw(),
        );
        assert.equal(categories.body.items.length, 1);
        assert.deepEqual(categories.body.items[0], {
            product: CHECKING.product,
            subProduct: CHECKING.subProduct,
            issue: CHECKING.issue,
            subIssue: null,
        });

        const cfpbPage = await validateRaw(
            'listCases',
            client.cases.listCasesRaw({ page: 0, size: 1, source: 'CFPB' }),
        );
        assert.equal(cfpbPage.body.totalElements, 2);
        assert.equal(cfpbPage.body.items.length, 1);
        assert.equal(cfpbPage.body.hasNext, true);
        const cfpbId = cfpbPage.body.items[0].id;

        const analystPage = await validateRaw(
            'listCases',
            client.cases.listCasesRaw({ page: 0, size: 20, source: 'ANALYST' }),
        );
        assert.equal(analystPage.body.totalElements, 0);

        const cfpbDetail = await validateRaw(
            'getCase',
            client.cases.getCaseRaw({ id: cfpbId }),
        );
        assert.equal(cfpbDetail.body.source, 'CFPB');
        assert.equal(cfpbDetail.body.analystNotes, null);
        assert.equal(cfpbDetail.body.provenance.importBatchId, batchId);
        assert.equal(typeof cfpbDetail.body.provenance.complaintId, 'string');
        assert.match(cfpbDetail.body.provenance.complaintId, /^[0-9]+$/);
        assert.ok(
            cfpbDetail.body.provenance.complaintId === '9007199254740993'
                || cfpbDetail.body.provenance.complaintId === '990000002',
        );
        assert.equal(cfpbDetail.body.referenceCaseIds.length, 0);

        const matching = await validateRaw(
            'searchCases',
            client.search.searchCasesRaw({
                searchRequest: {
                    ...CHECKING,
                    narrative: 'My transfer is missing from my checking account.',
                },
            }),
        );
        assert.equal(matching.body.status, 'MATCHES');
        assert.equal(matching.body.returnedCount, matching.body.results.length);
        assert.ok(matching.body.returnedCount <= matching.body.limit);
        assert.equal(matching.body.corpusSize, 2);
        const match = matching.body.results[0];
        assert.equal(match.case.source, 'CFPB');
        assert.equal(match.categoryExplanation.length, 4);
        assert.ok(match.sharedTerms.length <= 10);
        assert.ok(match.score >= 0 && match.score <= 1);
        assert.equal(matching.body.configuration.requiresTextOverlap, true);
        assert.equal(matching.body.configuration.algorithmVersion, 'tfidf-cosine-v1');

        const nonmatching = await validateRaw(
            'searchCases',
            client.search.searchCasesRaw({
                searchRequest: {
                    ...CHECKING,
                    narrative: 'zebra quantum volcano',
                },
            }),
        );
        assert.equal(nonmatching.body.status, 'NO_RELEVANT_CASES');
        assert.deepEqual(nonmatching.body.results, []);

        const retained = await validateRaw(
            'retainCase',
            client.cases.retainCaseRaw({
                retainCaseRequest: retainRequest({
                    referenceCaseIds: new Set([cfpbId]),
                }),
            }),
        );
        assert.equal(retained.response.status, 201);
        const analystId = retained.body.id;
        assert.equal(retained.response.headers.get('Location'), `/api/cases/${analystId}`);
        assert.equal(retained.body.source, 'ANALYST');
        assert.equal(retained.body.provenance, null);
        assert.equal(retained.body.analystNotes.handlingNotes,
            'Reviewed transfer evidence and requested a trace.');
        assert.deepEqual(retained.body.referenceCaseIds, [cfpbId]);

        const analystDetail = await validateRaw(
            'getCase',
            client.cases.getCaseRaw({ id: analystId }),
        );
        assert.equal(analystDetail.body.analystNotes.outcomeAssessment, 'UNKNOWN');
        assert.equal(analystDetail.body.provenance, null);

        const afterRetain = await validateRaw(
            'searchCases',
            client.search.searchCasesRaw({
                searchRequest: {
                    ...CHECKING,
                    narrative: 'My transfer is missing from my checking account.',
                },
            }),
        );
        assert.equal(afterRetain.body.corpusSize, 3);
        assert.ok(afterRetain.body.results.some((result) => result.case.id === analystId));

        const replacement = await validateRaw(
            'replaceAnalystCase',
            client.cases.replaceAnalystCaseRaw({
                id: analystId,
                retainCaseRequest: {
                    product: CHECKING.product,
                    issue: CHECKING.issue,
                    narrative: 'A replacement narrative with only required fields.',
                    handlingNotes: 'Replacement handling.',
                    outcomeAssessment: 'RESOLVED',
                },
            }),
        );
        assert.equal(replacement.response.status, 200);
        assert.equal(replacement.body.id, analystId);
        assert.equal(replacement.body.createdAt, retained.body.createdAt);
        assert.ok(Date.parse(replacement.body.updatedAt) >= Date.parse(retained.body.updatedAt));
        assert.equal(replacement.body.subProduct, null);
        assert.equal(replacement.body.receivedDate, null);
        assert.equal(replacement.body.company, null);
        assert.equal(replacement.body.companyResponse, null);
        assert.deepEqual(replacement.body.referenceCaseIds, []);
        assert.equal(replacement.body.analystNotes.outcomeAssessment, 'RESOLVED');
        assert.equal(replacement.body.analystNotes.evaluationNotes, null);

        const immutable = await fetchJson(
            server.baseUrl,
            `/api/cases/${cfpbId}`,
            {
                method: 'PUT',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    product: CHECKING.product,
                    issue: CHECKING.issue,
                    narrative: 'Attempt to rewrite an archived case.',
                    handlingNotes: 'Handling.',
                    outcomeAssessment: 'UNKNOWN',
                }),
            },
        );
        assert.equal(immutable.response.status, 409);
        assertResponse(
            'replaceAnalystCase',
            immutable.response.status,
            immutable.response.headers,
            immutable.body,
        );
        assert.equal(immutable.body.code, 'IMMUTABLE_CFPB_CASE');

        await assert.rejects(
            client.cases.replaceAnalystCase({
                id: cfpbId,
                retainCaseRequest: retainRequest(),
            }),
            (error) => {
                assert.ok(error instanceof ApiClientError);
                assert.equal(error.status, 409);
                assert.equal(error.code, 'IMMUTABLE_CFPB_CASE');
                return true;
            },
        );

        const missingCase = await fetchJson(
            server.baseUrl,
            '/api/cases/00000000-0000-4000-8000-000000000000',
        );
        assert.equal(missingCase.response.status, 404);
        assertResponse('getCase', 404, missingCase.response.headers, missingCase.body);
        assert.equal(missingCase.body.code, 'CASE_NOT_FOUND');

        const malformedUuid = await fetchJson(server.baseUrl, '/api/cases/1-1-1-1-1');
        assert.equal(malformedUuid.response.status, 400);
        assertResponse('getCase', 400, malformedUuid.response.headers, malformedUuid.body);
        assert.ok(['MALFORMED_REQUEST', 'VALIDATION_ERROR'].includes(malformedUuid.body.code));

        const malformedJson = await fetchJson(server.baseUrl, '/api/search', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: '{',
        });
        assert.equal(malformedJson.response.status, 400);
        assertResponse('searchCases', 400, malformedJson.response.headers, malformedJson.body);
        assert.equal(malformedJson.body.code, 'MALFORMED_REQUEST');

        const badPage = await fetchJson(server.baseUrl, '/api/cases?page=abc');
        assert.equal(badPage.response.status, 400);
        assertResponse('listCases', 400, badPage.response.headers, badPage.body);

        const unknownField = await fetchJson(server.baseUrl, '/api/search', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ ...CHECKING, narrative: 'A valid narrative.', source: 'CFPB' }),
        });
        assert.equal(unknownField.response.status, 400);
        assertResponse('searchCases', 400, unknownField.response.headers, unknownField.body);
        assert.equal(unknownField.body.code, 'VALIDATION_ERROR');

        const nullLimit = await fetchJson(server.baseUrl, '/api/search', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ ...CHECKING, narrative: 'A valid narrative.', limit: null }),
        });
        assert.equal(nullLimit.response.status, 400);
        assertResponse('searchCases', 400, nullLimit.response.headers, nullLimit.body);

        const invalidReference = await fetchJson(server.baseUrl, '/api/cases', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(retainRequest({
                referenceCaseIds: ['11111111-1111-4111-8111-111111111111'],
            })),
        });
        assert.equal(invalidReference.response.status, 400);
        assertResponse('retainCase', 400, invalidReference.response.headers,
            invalidReference.body);
        assert.equal(invalidReference.body.code, 'INVALID_REFERENCE');

        const nullReferences = await fetchJson(server.baseUrl, '/api/cases', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(retainRequest({ referenceCaseIds: null })),
        });
        assert.equal(nullReferences.response.status, 400);
        assertResponse('retainCase', 400, nullReferences.response.headers,
            nullReferences.body);

        const malformedDate = await fetchJson(server.baseUrl, '/api/cases', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(retainRequest({ receivedDate: '2024-13-40' })),
        });
        assert.equal(malformedDate.response.status, 400);
        assertResponse('retainCase', 400, malformedDate.response.headers,
            malformedDate.body);

        const methodNotAllowed = await fetchJson(server.baseUrl, '/api/health', {
            method: 'PATCH',
        });
        assert.equal(methodNotAllowed.response.status, 405);
        assertResponse('getHealth', 405, methodNotAllowed.response.headers,
            methodNotAllowed.body);

        const unsupportedType = await fetchJson(server.baseUrl, '/api/cases', {
            method: 'POST',
            headers: { 'Content-Type': 'text/plain' },
            body: JSON.stringify(retainRequest()),
        });
        assert.equal(unsupportedType.response.status, 415);
        assertResponse('retainCase', 415, unsupportedType.response.headers,
            unsupportedType.body);

        const routeNotFound = await fetchJson(server.baseUrl, '/api/does-not-exist');
        assert.equal(routeNotFound.response.status, 404);
        assertErrorSchema(routeNotFound.body);
        assert.equal(routeNotFound.body.code, 'ROUTE_NOT_FOUND');

        const duplicatedPrefix = await fetchJson(server.baseUrl, '/api/api/health');
        assert.equal(duplicatedPrefix.response.status, 404);
        assertErrorSchema(duplicatedPrefix.body);

        const preflight = await fetch(`${server.baseUrl}/api/cases`, {
            method: 'OPTIONS',
            headers: {
                Origin: 'http://localhost:3000',
                'Access-Control-Request-Method': 'POST',
                'Access-Control-Request-Headers': 'content-type, accept',
            },
        });
        await preflight.body?.cancel();
        assert.equal(preflight.status, 204);
        assert.equal(
            preflight.headers.get('Access-Control-Allow-Origin'),
            'http://localhost:3000',
        );
        assert.match(
            preflight.headers.get('Access-Control-Allow-Methods') ?? '',
            /POST/,
        );
        assert.match(
            preflight.headers.get('Access-Control-Expose-Headers') ?? '',
            /Location/,
        );

        const oversizedForm = new FormData();
        oversizedForm.append(
            'file',
            new Blob([new Uint8Array(21 * 1024 * 1024)], { type: 'text/csv' }),
            'oversized.csv',
        );
        oversizedForm.append(
            'metadata',
            new Blob([JSON.stringify(METADATA)], { type: 'application/json' }),
        );
        const oversized = await fetch(`${server.baseUrl}/api/imports/cfpb`, {
            method: 'POST',
            body: oversizedForm,
        });
        const oversizedBody = await oversized.json();
        assert.equal(oversized.status, 413);
        assertResponse('importCfpb', 413, oversized.headers, oversizedBody);

        const locked = new DatabaseSync(server.databasePath);
        try {
            locked.exec('PRAGMA busy_timeout = 0');
            locked.exec('BEGIN IMMEDIATE');
            const busy = await fetchJson(server.baseUrl, '/api/cases', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: rawRetainBody(),
            });
            assert.equal(busy.response.status, 503);
            assertResponse('retainCase', 503, busy.response.headers, busy.body);
            assert.equal(busy.body.code, 'DATABASE_BUSY');
            locked.exec('ROLLBACK');
        } finally {
            locked.close();
        }

        const schemaFixture = new DatabaseSync(server.databasePath);
        try {
            schemaFixture.exec('DROP TABLE analyst_notes');
        } finally {
            schemaFixture.close();
        }
        const internal = await fetchJson(server.baseUrl, '/api/cases', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: rawRetainBody({ referenceCaseIds: [] }),
        });
        assert.equal(internal.response.status, 500);
        assertResponse('retainCase', 500, internal.response.headers, internal.body);
        assert.equal(internal.body.code, 'INTERNAL_ERROR');

        const fillRows = [];
        for (let index = 0; index < 4_997; index += 1) {
            fillRows.push(csvRow(
                String(10_000_000 + index),
                `A synthetic capacity narrative number ${index}.`,
            ));
        }
        const filled = await validateRaw(
            'importCfpb',
            client.imports.importCfpbRaw({
                file: csvBytes(fillRows),
                metadata: {
                    ...METADATA,
                    sourceFileName: 'finresolve-live-capacity.csv',
                },
            }),
        );
        assert.equal(filled.response.status, 201);
        assert.equal(filled.body.imported, 4_997);

        const capacity = await fetchJson(server.baseUrl, '/api/cases', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: rawRetainBody({ referenceCaseIds: [] }),
        });
        assert.equal(capacity.response.status, 409);
        assertResponse('retainCase', 409, capacity.response.headers, capacity.body);
        assert.equal(capacity.body.code, 'KNOWLEDGE_BASE_LIMIT_EXCEEDED');
    } finally {
        await server.stop();
    }
});
