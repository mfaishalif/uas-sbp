// Contract, validation, pagination, headers, negotiation, and CORS probes.
// Usage: node docs/testing/probes/contract-validation.mjs <baseUrl> <output.json>
import assert from 'node:assert/strict';
import { createSession } from './lib.mjs';

const [baseUrl, output] = process.argv.slice(2);
if (!baseUrl || !output) {
    throw new Error('usage: contract-validation.mjs <baseUrl> <output.json>');
}

const session = createSession({ name: 'contract-validation', baseUrl, output });
const CATEGORIES = {
    product: 'Checking or savings account',
    issue: 'Managing an account',
    narrative: 'My transfer is missing from my checking account.',
};
const RETAIN = {
    ...CATEGORIES,
    handlingNotes: 'Contract probe notes.',
    outcomeAssessment: 'UNKNOWN',
    referenceCaseIds: [],
};

await session.check(
    'CTR-01',
    'All eight public operations return their documented success shape on a populated database.',
    async () => {
        const health = await session.request('/api/health');
        assert.equal(health.status, 200);
        assert.deepEqual(health.data, { status: 'UP', database: 'UP' });
        const categories = await session.request('/api/categories');
        assert.equal(categories.status, 200);
        assert.ok(categories.data.items.length > 0);
        const list = await session.request('/api/cases?size=2');
        assert.equal(list.status, 200);
        assert.equal(list.data.items.length, 2);
        const detail = await session.request(`/api/cases/${list.data.items[0].id}`);
        assert.equal(detail.status, 200);
        assert.ok('narrative' in detail.data);
        const search = await session.request('/api/search', { method: 'POST', body: CATEGORIES });
        assert.equal(search.status, 200);
        assert.ok(['MATCHES', 'NO_RELEVANT_CASES'].includes(search.data.status));
        const sample = await session.request('/api/imports/cfpb');
        assert.equal(sample.status, 405);
        const create = await session.request('/api/cases', { method: 'POST', body: RETAIN });
        assert.equal(create.status, 201);
        assert.equal(create.headers.get('location'), `/api/cases/${create.data.id}`);
        const update = await session.request(`/api/cases/${create.data.id}`, {
            method: 'PUT',
            body: { ...RETAIN, handlingNotes: 'Updated contract probe notes.' },
        });
        assert.equal(update.status, 200);
        return { detailId: detail.data.id, createdId: create.data.id };
    },
);

await session.check(
    'CTR-02',
    'Pagination is stable when data is unchanged, covers every case once, and filters by source before counting.',
    async () => {
        const first = await session.request('/api/cases?size=100');
        const ids = [];
        for (let page = 0; page < first.data.totalPages; page += 1) {
            const response = await session.request(`/api/cases?page=${page}&size=100`);
            assert.equal(response.data.totalPages, first.data.totalPages);
            assert.equal(response.data.totalElements, first.data.totalElements);
            assert.equal(response.data.hasNext, page + 1 < first.data.totalPages);
            ids.push(...response.data.items.map((item) => item.id));
        }
        assert.equal(ids.length, first.data.totalElements);
        assert.equal(new Set(ids).size, ids.length);
        const repeated = [];
        for (let page = 0; page < first.data.totalPages; page += 1) {
            const response = await session.request(`/api/cases?page=${page}&size=100`);
            repeated.push(...response.data.items.map((item) => item.id));
        }
        assert.deepEqual(repeated, ids);
        const cfpb = await session.request('/api/cases?source=CFPB');
        const analyst = await session.request('/api/cases?source=ANALYST');
        assert.equal(cfpb.data.totalElements + analyst.data.totalElements, first.data.totalElements);
        assert.ok(cfpb.data.items.every((item) => item.source === 'CFPB'));
        assert.ok(analyst.data.items.every((item) => item.source === 'ANALYST'));
        const beyond = await session.request('/api/cases?page=2147483647&size=100');
        assert.deepEqual(beyond.data.items, []);
        assert.equal(beyond.data.totalElements, first.data.totalElements);
        return {
            total: first.data.totalElements,
            cfpb: cfpb.data.totalElements,
            analyst: analyst.data.totalElements,
        };
    },
);

for (const [query, name] of [
    ['page=-1', 'negative page'],
    ['page=abc', 'non-numeric page'],
    ['page=1.5', 'fraction page'],
    ['page=', 'empty page'],
    ['size=0', 'zero size'],
    ['size=101', 'size above maximum'],
    ['size=', 'empty size'],
    ['source=', 'empty source'],
    ['source=cfpb', 'lowercase source'],
    ['source=UNKNOWN', 'unknown source'],
]) {
    await session.check(
        `CTR-03-${name}`,
        `List rejects ${name} with a 400 contract error.`,
        async () => {
            const response = await session.request(`/api/cases?${query}`);
            assert.equal(response.status, 400);
            assert.ok(['VALIDATION_ERROR', 'MALFORMED_REQUEST'].includes(response.data.code));
            return { status: response.status, code: response.data.code, fieldErrors: response.data.fieldErrors };
        },
    );
}

await session.check(
    'CTR-04',
    'Required fields, explicit nulls, wrong JSON types, unknown properties, malformed UUIDs, dates, and unknown fields are rejected.',
    async () => {
        const cases = [
            ['/api/search', { ...CATEGORIES, product: null }],
            ['/api/search', { ...CATEGORIES, issue: '   ' }],
            ['/api/search', { ...CATEGORIES, narrative: null }],
            ['/api/search', { ...CATEGORIES, narrative: 'the and of' }],
            ['/api/search', { ...CATEGORIES, limit: null }],
            ['/api/search', { ...CATEGORIES, limit: 0 }],
            ['/api/search', { ...CATEGORIES, limit: 21 }],
            ['/api/search', { ...CATEGORIES, limit: 1.5 }],
            ['/api/search', { ...CATEGORIES, product: [] }],
            ['/api/search', { ...CATEGORIES, source: 'CFPB' }],
            ['/api/cases', { ...RETAIN, referenceCaseIds: null }],
            ['/api/cases', { ...RETAIN, referenceCaseIds: ['1-1-1-1-1'] }],
            ['/api/cases', { ...RETAIN, referenceCaseIds: [null] }],
            ['/api/cases', { ...RETAIN, receivedDate: '2024-02-30' }],
            ['/api/cases', { ...RETAIN, receivedDate: '+10000-01-01' }],
            ['/api/cases', { ...RETAIN, outcomeAssessment: 'resolved' }],
            ['/api/cases', { ...RETAIN, handlingNotes: '   ' }],
            ['/api/cases', { ...RETAIN, id: '11111111-1111-4111-8111-111111111111' }],
        ];
        const results = [];
        for (const [path, body] of cases) {
            const response = await session.request(path, { method: 'POST', body });
            assert.equal(response.status, 400, `${path} ${JSON.stringify(body)}`);
            results.push({ path, status: response.status, code: response.data.code });
        }
        const malformed = await session.request('/api/search', {
            method: 'POST',
            raw: '{',
            headers: { 'Content-Type': 'application/json' },
        });
        assert.equal(malformed.status, 400);
        assert.equal(malformed.data.code, 'MALFORMED_REQUEST');
        return results;
    },
);

await session.check(
    'CTR-05',
    'Exact Unicode code-point boundaries are enforced for narrative, company, notes, and evaluation notes.',
    async () => {
        const attempts = [
            ['narrative-50000', { narrative: 'a'.repeat(50000) }, 201],
            ['narrative-50001', { narrative: 'a'.repeat(50001) }, 400],
            ['company-500', { company: '😀'.repeat(500) }, 201],
            ['company-501', { company: '😀'.repeat(501) }, 400],
            ['handling-10000', { handlingNotes: '😀'.repeat(10000) }, 201],
            ['handling-10001', { handlingNotes: '😀'.repeat(10001) }, 400],
            ['evaluation-10000', { evaluationNotes: '😀'.repeat(10000) }, 201],
            ['evaluation-10001', { evaluationNotes: '😀'.repeat(10001) }, 400],
        ];
        const results = [];
        for (const [name, patch, expected] of attempts) {
            const response = await session.request('/api/cases', {
                method: 'POST',
                body: { ...RETAIN, ...patch },
            });
            assert.equal(response.status, expected, name);
            results.push({ name, status: response.status });
        }
        return results;
    },
);

await session.check(
    'CTR-06',
    'Reference order is preserved and the full PUT clears omitted optional fields while preserving identity.',
    async () => {
        const page = await session.request('/api/cases?source=CFPB&size=5');
        const references = page.data.items.map((item) => item.id).reverse();
        const created = await session.request('/api/cases', {
            method: 'POST',
            body: {
                ...RETAIN,
                subProduct: null,
                subIssue: null,
                receivedDate: '2024-12-01',
                company: 'Probe Bank',
                companyResponse: 'Closed with explanation',
                evaluationNotes: 'Probe evaluation.',
                referenceCaseIds: references,
            },
        });
        assert.equal(created.status, 201);
        assert.deepEqual(created.data.referenceCaseIds, references);
        const replacement = {
            ...RETAIN,
            narrative: 'Replacement contract narrative.',
            handlingNotes: 'Replacement notes.',
            outcomeAssessment: 'RESOLVED',
            referenceCaseIds: [],
        };
        const put = await session.request(`/api/cases/${created.data.id}`, { method: 'PUT', body: replacement });
        assert.equal(put.status, 200);
        assert.equal(put.data.id, created.data.id);
        assert.equal(put.data.createdAt, created.data.createdAt);
        for (const field of ['subProduct', 'subIssue', 'receivedDate', 'company', 'companyResponse']) {
            assert.equal(put.data[field], null);
        }
        assert.deepEqual(put.data.referenceCaseIds, []);
        assert.equal(put.data.analystNotes.evaluationNotes, null);
        return { id: created.data.id, referenceCount: references.length };
    },
);

await session.check(
    'CTR-07',
    'Exact and case-variant duplicate references are both rejected, while a single uppercase UUID is accepted.',
    async () => {
        const page = await session.request('/api/cases?source=CFPB&size=1');
        const id = page.data.items[0].id;
        const before = await session.request('/api/cases');
        const rejected = [];
        for (const [label, references] of [
            ['exact', [id, id]],
            ['case-variant', [id, id.toUpperCase()]],
        ]) {
            const response = await session.request('/api/cases', {
                method: 'POST',
                body: { ...RETAIN, referenceCaseIds: references },
            });
            assert.equal(response.status, 400);
            assert.equal(response.data.code, 'VALIDATION_ERROR');
            assert.ok(
                response.data.fieldErrors.some((error) =>
                    error.field === 'referenceCaseIds[1]' && error.code === 'DUPLICATE'),
                `${label} duplicate must report referenceCaseIds[1] DUPLICATE`,
            );
            rejected.push({ label, status: response.status, fieldErrors: response.data.fieldErrors });
        }
        const afterRejected = await session.request('/api/cases');
        assert.equal(afterRejected.data.totalElements, before.data.totalElements);
        const accepted = await session.request('/api/cases', {
            method: 'POST',
            body: { ...RETAIN, referenceCaseIds: [id.toUpperCase()] },
        });
        assert.equal(accepted.status, 201);
        assert.equal(accepted.data.referenceCaseIds.length, 1);
        assert.equal(accepted.data.referenceCaseIds[0], id);
        return {
            rejected,
            accepted: { status: accepted.status, referenceCaseIds: accepted.data.referenceCaseIds },
            totalElements: afterRejected.data.totalElements,
        };
    },
);

await session.check(
    'CTR-08',
    'Location, Allow, 404, 405, 415, and malformed-route responses follow the contract.',
    async () => {
        const created = await session.request('/api/cases', { method: 'POST', body: RETAIN });
        assert.equal(created.status, 201);
        assert.equal(created.headers.get('location'), `/api/cases/${created.data.id}`);
        const method = await session.request('/api/health', { method: 'PATCH' });
        assert.equal(method.status, 405);
        assert.match(method.headers.get('allow') ?? '', /GET/);
        const route = await session.request('/api/not-found');
        assert.equal(route.status, 404);
        assert.equal(route.data.code, 'ROUTE_NOT_FOUND');
        const caseMissing = await session.request('/api/cases/00000000-0000-4000-8000-000000000000');
        assert.equal(caseMissing.status, 404);
        assert.equal(caseMissing.data.code, 'CASE_NOT_FOUND');
        const wrongType = await session.request('/api/search', {
            method: 'POST',
            raw: JSON.stringify(CATEGORIES),
            headers: { 'Content-Type': 'text/plain' },
        });
        assert.equal(wrongType.status, 415);
        assert.equal(wrongType.data.code, 'UNSUPPORTED_MEDIA_TYPE');
        return {
            location: created.headers.get('location'),
            allow: method.headers.get('allow'),
            route: route.status,
            caseMissing: caseMissing.status,
            unsupported: wrongType.status,
        };
    },
);

await session.check(
    'CTR-09',
    'CORS allows the configured origin on actual requests and preflight, and rejects foreign origins and unapproved headers.',
    async () => {
        const actual = await session.request('/api/health', {
            headers: { Origin: 'http://localhost:3000' },
        });
        assert.equal(actual.status, 200);
        assert.equal(actual.headers.get('access-control-allow-origin'), 'http://localhost:3000');
        assert.match(actual.headers.get('access-control-expose-headers') ?? '', /Location/);
        const preflight = await session.request('/api/cases', {
            method: 'OPTIONS',
            headers: {
                Origin: 'http://localhost:3000',
                'Access-Control-Request-Method': 'POST',
                'Access-Control-Request-Headers': 'content-type, accept',
            },
        });
        assert.equal(preflight.status, 204);
        assert.equal(preflight.headers.get('access-control-allow-origin'), 'http://localhost:3000');
        const foreignActual = await session.request('/api/health', {
            headers: { Origin: 'https://foreign.example' },
        });
        assert.equal(foreignActual.status, 403);
        assert.equal(foreignActual.headers.get('access-control-allow-origin'), null);
        const foreignPreflight = await session.request('/api/cases', {
            method: 'OPTIONS',
            headers: { Origin: 'https://foreign.example', 'Access-Control-Request-Method': 'POST' },
        });
        assert.equal(foreignPreflight.status, 403);
        const badHeader = await session.request('/api/cases', {
            method: 'OPTIONS',
            headers: {
                Origin: 'http://localhost:3000',
                'Access-Control-Request-Method': 'POST',
                'Access-Control-Request-Headers': 'authorization',
            },
        });
        assert.equal(badHeader.status, 403);
        return { actual: actual.status, preflight: preflight.status, foreign: foreignActual.status };
    },
);

await session.check(
    'CTR-10',
    'Unsupported Accept is a controlled 406 and supported Accept values return JSON.',
    async () => {
        const unsupported = await session.request('/api/health', { headers: { Accept: 'application/xml' } });
        assert.equal(unsupported.status, 406);
        const wildcard = await session.request('/api/health', { headers: { Accept: '*/*' } });
        assert.equal(wildcard.status, 200);
        const json = await session.request('/api/health', { headers: { Accept: 'application/json' } });
        assert.equal(json.status, 200);
        assert.match(json.headers.get('content-type') ?? '', /application\/json/);
        return { unsupported: unsupported.status, wildcard: wildcard.status };
    },
);

await session.save();
