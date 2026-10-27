// Run against a fresh, disposable backend, never an existing demo database.
// node tools/exploratory-backend.mjs http://127.0.0.1:8080 /tmp/finresolve-exploratory.json
import assert from 'node:assert/strict';
import { readFile, writeFile } from 'node:fs/promises';
import { assertResponse, assertErrorSchema } from '../tests/contract/schema.mjs';

const base = process.argv[2] ?? 'http://127.0.0.1:8080';
const output = process.argv[3] ?? '/tmp/finresolve-exploratory.json';
const results = [];
const requests = [];
const context = {};
const search = {
    product: 'Checking or savings account',
    issue: 'Managing an account',
    narrative: 'My transfer is missing from my checking account.',
};
const retain = { ...search, handlingNotes: 'Exploratory test: requested a transfer trace.', outcomeAssessment: 'UNKNOWN' };
const metadata = {
    archiveUrl: 'https://files.consumerfinance.gov/f/documents/CCDB_Export_8_November_2024_through_December_2024.zip',
    sourceFileName: 'finresolve-sample.csv', periodStart: '2024-11-01', periodEnd: '2024-12-31',
};
const header = 'Complaint ID,Product,Issue,Consumer complaint narrative,Date received\n';
const row = (id, narrative = 'A synthetic missing transfer complaint.', date = '2024-11-12') =>
    `${id},Checking or savings account,Managing an account,"${narrative.replaceAll('"', '""')}",${date}\n`;
function form(csv, meta = metadata, { file = true, info = true, extra = false } = {}) {
    const value = new FormData();
    if (file) value.append('file', new Blob([csv], { type: 'text/csv' }), 'exploratory.csv');
    if (info) value.append('metadata', new Blob([JSON.stringify(meta)], { type: 'application/json' }));
    if (extra) value.append('unexpected', 'value');
    return value;
}
async function request(path, { method = 'GET', body, raw, headers = {} } = {}, operation) {
    const started = performance.now();
    const init = { method, headers: { ...headers }, signal: AbortSignal.timeout(30000) };
    if (raw !== undefined) init.body = raw;
    else if (body instanceof FormData) init.body = body;
    else if (body !== undefined) {
        init.headers['Content-Type'] ??= 'application/json';
        init.body = JSON.stringify(body);
    }
    const response = await fetch(base + path, init);
    const text = await response.text();
    let data;
    try { data = text ? JSON.parse(text) : null; } catch { data = text; }
    const evidence = {
        method, path, status: response.status, ms: Math.round(performance.now() - started),
        code: data?.code, contentType: response.headers.get('content-type'),
    };
    requests.push(evidence);
    if (operation) {
        try { assertResponse(operation, response.status, response.headers, data); }
        catch (error) { evidence.schemaError = error.message; throw error; }
    }
    if (response.status >= 400 && data && typeof data === 'object') {
        assertErrorSchema(data);
        assert.match(data.timestamp, /Z$/);
    }
    return { status: response.status, headers: response.headers, data, ms: evidence.ms };
}
async function check(name, fn) {
    const first = requests.length;
    try {
        const detail = await fn();
        results.push({ name, passed: true, detail, requests: requests.slice(first) });
        console.log(`PASS ${name}`);
    } catch (error) {
        results.push({ name, passed: false, error: error.message, requests: requests.slice(first) });
        console.log(`FAIL ${name}: ${error.message}`);
    }
}
const post = (path, body, operation) => request(path, { method: 'POST', body }, operation);
const expect = (r, status, code) => { assert.equal(r.status, status); if (code) assert.equal(r.data.code, code); return r.data; };
function scoreInvariants(data) {
    assert.equal(data.returnedCount, data.results.length);
    assert.ok(data.returnedCount <= data.limit);
    let previous = Infinity;
    for (const match of data.results) {
        for (const key of ['score', 'categoryScore', 'textScore']) assert.ok(match[key] >= 0 && match[key] <= 1);
        assert.ok(match.score <= previous); previous = match.score;
        assert.ok(match.score >= data.configuration.threshold && match.textScore > 0);
        assert.ok(Math.abs(match.score - (data.configuration.categoryWeight * match.categoryScore + data.configuration.textWeight * match.textScore)) < 1e-10);
        assert.equal(match.categoryExplanation.length, 4);
        assert.ok(Math.abs(match.categoryExplanation.reduce((s, item) => s + item.contribution, 0) - match.categoryScore) < 1e-10);
        assert.ok(match.sharedTerms.length <= 10);
    }
}

const initial = await request('/api/cases', {}, 'listCases');
if (initial.data.totalElements !== 0) throw new Error('Refusing to test a populated database. Use a fresh disposable database.');
await check('empty health/categories/list', async () => {
    expect(await request('/api/health', {}, 'getHealth'), 200);
    assert.deepEqual(expect(await request('/api/categories', {}, 'getCategories'), 200).items, []);
    assert.equal(initial.data.totalElements, 0);
});
await check('empty search validates narrative before returning EMPTY_KNOWLEDGE_BASE', async () => {
    assert.equal(expect(await post('/api/search', search, 'searchCases'), 200).status, 'EMPTY_KNOWLEDGE_BASE');
    expect(await post('/api/search', { ...search, narrative: 'the and 123 XXXX' }, 'searchCases'), 400, 'VALIDATION_ERROR');
});
await check('retention before import rejected', async () => expect(await post('/api/cases', retain, 'retainCase'), 409, 'EMPTY_CATEGORY_CATALOG'));
for (const [name, body] of [
    ['missing file', form('', metadata, { file: false })],
    ['missing metadata', form(header, metadata, { info: false })],
    ['empty file', form('')], ['header only', form(header)],
    ['missing required header', form('Product,Issue\nChecking,test\n')],
    ['duplicate header', form('Complaint ID,Product,Issue,Issue,Consumer complaint narrative\n')],
    ['broken quoted field', form(header + '999,Checking,Issue,"unterminated')],
    ['invalid UTF-8', form(new Uint8Array([0xff, 0xfe, 0xfd]))],
]) await check(`import rejects ${name}`, async () => expect(await post('/api/imports/cfpb', body, 'importCfpb'), 400));
for (const [name, patch] of [
    ['HTTP URL', { archiveUrl: 'http://example.com/archive.zip' }],
    ['URL without host', { archiveUrl: 'https://' }],
    ['path in source filename', { sourceFileName: '../../secret.csv' }],
    ['reversed period', { periodStart: '2025-01-01' }],
    ['impossible date', { periodStart: '2024-02-30' }],
    ['unknown property', { source: 'ANALYST' }],
]) await check(`import metadata rejects ${name}`, async () => expect(await post('/api/imports/cfpb', form(header + row('990000001'), { ...metadata, ...patch }), 'importCfpb'), 400));

const sample = await readFile(new URL('../data/cfpb/sample.csv', import.meta.url));
await check('import committed sample: 500 accepted and SHA-256 matches manifest', async () => {
    const data = expect(await post('/api/imports/cfpb', form(sample), 'importCfpb'), 201);
    assert.equal(data.processed, 500); assert.equal(data.imported, 500); assert.equal(data.rejected, 0); assert.equal(data.duplicates, 0);
    assert.equal(data.csvSha256, '682bb04cf77a7df10104c822ab24af4bd42e1a088cd2678c7c72921b7983a644');
    context.batchId = data.batchId;
    return { imported: data.imported, batchId: data.batchId };
});
await check('identical and whitespace-normalized import replay', async () => {
    for (const meta of [metadata, { ...metadata, archiveUrl: `  ${metadata.archiveUrl}  `, sourceFileName: `  ${metadata.sourceFileName}  ` }]) {
        const data = expect(await post('/api/imports/cfpb', form(sample, meta), 'importCfpb'), 200);
        assert.equal(data.replayed, true); assert.equal(data.batchId, context.batchId); assert.equal(data.imported, 500);
    }
});
await check('same CSV with different metadata reports 500 duplicates', async () => {
    const data = expect(await post('/api/imports/cfpb', form(sample, { ...metadata, sourceFileName: 'another-sample.csv' }), 'importCfpb'), 201);
    assert.equal(data.duplicates, 500); assert.equal(data.imported, 0);
});
await check('category tuple uniqueness and detail provenance', async () => {
    const categories = expect(await request('/api/categories', {}, 'getCategories'), 200);
    context.categories = categories;
    assert.ok(categories.items.length > 0);
    assert.equal(new Set(categories.items.map(x => JSON.stringify(x))).size, categories.items.length);
    const page = expect(await request('/api/cases?source=CFPB&size=1', {}, 'listCases'), 200);
    assert.equal(page.totalElements, 500);
    context.cfpb = expect(await request(`/api/cases/${page.items[0].id}`, {}, 'getCase'), 200);
    assert.equal(context.cfpb.provenance.importBatchId, context.batchId);
    return { tuples: categories.items.length };
});
await check('pagination covers 500 unique cases in stable order', async () => {
    const ids = [];
    for (let page = 0; page < 5; page++) {
        const data = expect(await request(`/api/cases?page=${page}&size=100`, {}, 'listCases'), 200);
        assert.equal(data.totalPages, 5); assert.equal(data.hasNext, page < 4);
        ids.push(...data.items.map(item => item.id));
    }
    assert.equal(ids.length, 500); assert.equal(new Set(ids).size, 500);
    assert.deepEqual(ids, [...ids].sort());
    for (const page of [5, 2147483647]) assert.deepEqual(expect(await request(`/api/cases?page=${page}&size=100`, {}, 'listCases'), 200).items, []);
});
for (const query of ['page=-1', 'size=0', 'size=101', 'page=abc', 'page=1.5', 'page=2147483648', 'page=', 'size=', 'source=', 'source=cfpb', 'source=UNKNOWN', "source=%27%20OR%201%3D1--"]) {
    await check(`list rejects ${query}`, async () => expect(await request(`/api/cases?${query}`, {}, 'listCases'), 400));
}
for (const id of ['1-1-1-1-1', 'not-a-uuid', '00000000-0000-4000-8000-000000000000']) {
    await check(`detail invalid/missing ${id}`, async () => expect(await request(`/api/cases/${id}`, {}, 'getCase'), id.startsWith('00000000') ? 404 : 400));
}
await check('exact narrative retrieval ranks original CFPB case first', async () => {
    const original = context.cfpb;
    const data = expect(await post('/api/search', { product: original.product, subProduct: original.subProduct, issue: original.issue, subIssue: original.subIssue, narrative: original.narrative, limit: 20 }, 'searchCases'), 200);
    scoreInvariants(data); assert.equal(data.status, 'MATCHES');
    assert.equal(data.results[0].case.id, original.id); assert.ok(Math.abs(data.results[0].score - 1) < 1e-10);
    return { topScore: data.results[0].score, returnedCount: data.returnedCount, ms: requests.at(-1).ms };
});
await check('search NO_RELEVANT_CASES and no persistence', async () => {
    const data = expect(await post('/api/search', { ...search, narrative: 'qzxvplm xwzqrpt' }, 'searchCases'), 200);
    assert.equal(data.status, 'NO_RELEVANT_CASES'); assert.deepEqual(data.results, []);
    assert.equal(expect(await request('/api/cases', {}, 'listCases'), 200).totalElements, 500);
});
await check('case/spacing normalization and optional blank categories', async () => {
    const canonical = expect(await post('/api/search', search, 'searchCases'), 200);
    const normalized = expect(await post('/api/search', { ...search, product: '  CHECKING  OR SAVINGS ACCOUNT  ', issue: ' managing   AN ACCOUNT ', subProduct: '   ', subIssue: null }, 'searchCases'), 200);
    assert.deepEqual(canonical.results, normalized.results);
});
const invalidSearch = [
    ['unknown category', { product: 'Unknown product' }], ['invalid issue', { issue: 'Unknown issue' }],
    ['wrong subproduct', { subProduct: 'International money transfer' }], ['wrong subissue', { subIssue: 'Unknown issue' }],
    ['blank required field', { product: '   ' }], ['empty narrative', { narrative: '' }],
    ['stopwords only', { narrative: 'the and or of' }], ['numbers only', { narrative: '123 456 2024' }],
    ['redaction only', { narrative: 'XXXX XX XXXX' }], ['Unicode punctuation only', { narrative: '😀💰。' }],
    ['narrative over 50000', { narrative: 'transfer '.repeat(6251) }],
    ['category over 500', { product: 'a'.repeat(501) }], ['unknown field', { source: 'CFPB' }],
    ['null limit', { limit: null }], ['zero limit', { limit: 0 }], ['over limit', { limit: 21 }],
    ['fraction limit', { limit: 1.5 }], ['string limit', { limit: '5' }], ['null narrative', { narrative: null }],
    ['array category', { product: [] }],
];
for (const [name, patch] of invalidSearch) await check(`search rejects ${name}`, async () => expect(await post('/api/search', { ...search, ...patch }, 'searchCases'), 400));
for (const raw of ['{', 'null', '[]', '42', '{}', '', JSON.stringify(search) + '{}']) {
    await check(`search rejects malformed/invalid object ${JSON.stringify(raw).slice(0, 55)}`, async () => expect(await request('/api/search', { method: 'POST', raw, headers: { 'Content-Type': 'application/json' } }, 'searchCases'), 400));
}
await check('search accepts limit boundaries and 50000-character narrative', async () => {
    for (const limit of [1, 20]) scoreInvariants(expect(await post('/api/search', { ...search, limit }, 'searchCases'), 200));
    scoreInvariants(expect(await post('/api/search', { ...search, narrative: 'transfer '.repeat(5556).slice(0, 50000) }, 'searchCases'), 200));
});

// The committed evaluation CSV contains one record per line; honor quoted commas/quotes.
function csvFields(line) {
    const fields = []; let field = ''; let quoted = false;
    for (let i = 0; i < line.length; i++) {
        const c = line[i];
        if (c === '"' && line[i + 1] === '"' && quoted) { field += '"'; i++; }
        else if (c === '"') quoted = !quoted;
        else if (c === ',' && !quoted) { fields.push(field); field = ''; }
        else field += c;
    }
    fields.push(field); return fields;
}
context.evaluation = [];
const queries = (await readFile(new URL('../docs/evaluation/queries.csv', import.meta.url), 'utf8')).trim().split(/\r?\n/).slice(1);
for (const line of queries) {
    const [queryId, product, subProduct, issue, subIssue, narrative, scenario] = csvFields(line);
    await check(`evaluation query ${queryId}: ${scenario}`, async () => {
        const response = await post('/api/search', { product, subProduct: subProduct || null, issue, subIssue: subIssue || null, narrative }, 'searchCases');
        const data = expect(response, 200); scoreInvariants(data); assert.equal(data.corpusSize, 500);
        const observation = { queryId, scenario, status: data.status, returnedCount: data.returnedCount, ms: response.ms, topIds: data.results.map(x => x.case.id), topScores: data.results.map(x => x.score) };
        context.evaluation.push(observation); return observation;
    });
}

await check('create analyst with notes, date, reference and Location', async () => {
    const response = await post('/api/cases', { ...retain, subProduct: 'Checking account', receivedDate: '2024-02-29', company: '  Example Test Bank  ', evaluationNotes: '  Pending investigation.  ', referenceCaseIds: [context.cfpb.id] }, 'retainCase');
    const data = expect(response, 201); context.analyst = data;
    assert.equal(response.headers.get('Location'), `/api/cases/${data.id}`);
    assert.equal(data.company, 'Example Test Bank'); assert.equal(data.source, 'ANALYST'); assert.equal(data.provenance, null);
    assert.deepEqual(data.referenceCaseIds, [context.cfpb.id]);
    assert.deepEqual(expect(await request(`/api/cases/${data.id}`, {}, 'getCase'), 200), data);
    return { id: data.id };
});
await check('analyst is immediately searchable; category catalog stays unchanged', async () => {
    const data = expect(await post('/api/search', { ...search, limit: 20 }, 'searchCases'), 200);
    scoreInvariants(data); assert.equal(data.corpusSize, 501);
    assert.ok(data.results.some(x => x.case.id === context.analyst.id));
    assert.deepEqual(expect(await request('/api/categories', {}, 'getCategories'), 200), context.categories);
});
const invalidRetain = [
    ['missing handling notes', { handlingNotes: undefined }], ['blank notes', { handlingNotes: '   ' }],
    ['notes over 10000', { handlingNotes: 'a'.repeat(10001) }], ['evaluation over 10000', { evaluationNotes: 'a'.repeat(10001) }],
    ['company over 500', { company: 'a'.repeat(501) }], ['response over 2000', { companyResponse: 'a'.repeat(2001) }],
    ['invalid outcome', { outcomeAssessment: 'resolved' }], ['invalid date', { receivedDate: '2024-02-30' }],
    ['null reference array', { referenceCaseIds: null }], ['unknown reference', { referenceCaseIds: ['11111111-1111-4111-8111-111111111111'] }],
    ['duplicate references', { referenceCaseIds: [context.cfpb.id, context.cfpb.id] }],
    ['abbreviated reference UUID', { referenceCaseIds: ['1-1-1-1-1'] }], ['null reference member', { referenceCaseIds: [null] }],
    ['21 references', { referenceCaseIds: Array.from({ length: 21 }, (_, n) => `11111111-1111-4111-8111-${String(n).padStart(12, '0')}`) }],
    ['source injection', { source: 'CFPB' }], ['id injection', { id: context.cfpb.id }],
];
for (const [name, patch] of invalidRetain) await check(`retain rejects ${name}`, async () => expect(await post('/api/cases', { ...retain, ...patch }, 'retainCase'), 400));
await check('failed retention is atomic', async () => assert.equal(expect(await request('/api/cases', {}, 'listCases'), 200).totalElements, 501));
await check('CFPB update immutable; missing case and self-reference rejected', async () => {
    expect(await request(`/api/cases/${context.cfpb.id}`, { method: 'PUT', body: retain }, 'replaceAnalystCase'), 409, 'IMMUTABLE_CFPB_CASE');
    expect(await request('/api/cases/00000000-0000-4000-8000-000000000000', { method: 'PUT', body: retain }, 'replaceAnalystCase'), 404, 'CASE_NOT_FOUND');
    expect(await request(`/api/cases/${context.analyst.id}`, { method: 'PUT', body: { ...retain, referenceCaseIds: [context.analyst.id] } }, 'replaceAnalystCase'), 400, 'INVALID_REFERENCE');
    assert.deepEqual(expect(await request(`/api/cases/${context.analyst.id}`, {}, 'getCase'), 200), context.analyst);
});
await check('full PUT clears omitted fields and preserves identity/createdAt', async () => {
    const original = context.analyst;
    const data = expect(await request(`/api/cases/${original.id}`, { method: 'PUT', body: { ...retain, outcomeAssessment: 'RESOLVED' } }, 'replaceAnalystCase'), 200);
    assert.equal(data.id, original.id); assert.equal(data.createdAt, original.createdAt); assert.ok(Date.parse(data.updatedAt) >= Date.parse(original.updatedAt));
    for (const key of ['company', 'companyResponse', 'subProduct', 'subIssue', 'receivedDate']) assert.equal(data[key], null);
    assert.deepEqual(data.referenceCaseIds, []); assert.equal(data.analystNotes.evaluationNotes, null);
    context.analyst = data;
});
await check('Unicode supplementary characters respect code-point length', async () => {
    const data = expect(await post('/api/cases', { ...retain, company: '😀'.repeat(500), handlingNotes: '😀'.repeat(10000) }, 'retainCase'), 201);
    assert.equal([...data.company].length, 500);
    expect(await post('/api/cases', { ...retain, company: '😀'.repeat(501) }, 'retainCase'), 400);
});
await check('concurrent retention preserves all six writes', async () => {
    const before = expect(await request('/api/cases?source=ANALYST', {}, 'listCases'), 200).totalElements;
    const responses = await Promise.all(Array.from({ length: 6 }, (_, index) => post('/api/cases', { ...retain, handlingNotes: `Concurrent exploratory ${index}` }, 'retainCase')));
    for (const response of responses) expect(response, 201);
    assert.equal(new Set(responses.map(r => r.data.id)).size, 6);
    assert.equal(expect(await request('/api/cases?source=ANALYST', {}, 'listCases'), 200).totalElements, before + 6);
});
await check('concurrent identical imports replay the same batch once', async () => {
    const csv = header + row('999800001');
    const responses = await Promise.all(Array.from({ length: 3 }, () => post('/api/imports/cfpb', form(csv), 'importCfpb')));
    assert.deepEqual(responses.map(r => r.status).sort(), [200, 200, 201]);
    assert.equal(new Set(responses.map(r => r.data.batchId)).size, 1);
});
await check('mixed CSV reports rejected rows and duplicates without overwriting', async () => {
    const csv = header + row('999800002') + row('999800002', 'A changed duplicate complaint.') + row('bad-id') + row('999800003', 'the and 123') + row('999800004', 'Missing transfer.', '2025-01-01');
    const data = expect(await post('/api/imports/cfpb', form(csv), 'importCfpb'), 201);
    assert.equal(data.processed, 5); assert.equal(data.imported, 1); assert.equal(data.duplicates, 1); assert.equal(data.rejected, 3);
    return { processed: data.processed, imported: data.imported, duplicates: data.duplicates, rejected: data.rejected };
});
await check('CSV record limit is atomic', async () => {
    const before = expect(await request('/api/cases', {}, 'listCases'), 200).totalElements;
    expect(await post('/api/imports/cfpb', form(header + Array.from({ length: 5001 }, (_, i) => row(999810000 + i)).join('')), 'importCfpb'), 400, 'IMPORT_LIMIT_EXCEEDED');
    assert.equal(expect(await request('/api/cases', {}, 'listCases'), 200).totalElements, before);
});
await check('multipart file over 20 MiB rejected with JSON 413', async () => expect(await post('/api/imports/cfpb', form(new Uint8Array(20 * 1024 * 1024 + 1)), 'importCfpb'), 413, 'PAYLOAD_TOO_LARGE'));
for (const [path, method, status, operation] of [
    ['/api/health', 'PATCH', 405, 'getHealth'], ['/api/cases', 'DELETE', 405, 'listCases'],
    ['/api/search', 'GET', 405, 'searchCases'], ['/api/imports/cfpb', 'GET', 405, 'importCfpb'],
    ['/api/not-found', 'GET', 404], ['/api/api/health', 'GET', 404],
]) await check(`routing ${method} ${path}`, async () => expect(await request(path, { method }, operation), status));
await check('wrong content type returns 415', async () => expect(await request('/api/search', { method: 'POST', raw: JSON.stringify(search), headers: { 'Content-Type': 'text/plain' } }, 'searchCases'), 415, 'UNSUPPORTED_MEDIA_TYPE'));
await check('allowed CORS preflight and actual response', async () => {
    const preflight = await request('/api/cases', { method: 'OPTIONS', headers: { Origin: 'http://localhost:3000', 'Access-Control-Request-Method': 'POST', 'Access-Control-Request-Headers': 'content-type, accept' } });
    expect(preflight, 204); assert.equal(preflight.data, null); assert.equal(preflight.headers.get('access-control-allow-origin'), 'http://localhost:3000');
    const actual = await request('/api/health', { headers: { Origin: 'http://localhost:3000' } }, 'getHealth');
    assert.equal(actual.headers.get('access-control-allow-origin'), 'http://localhost:3000');
    assert.match(actual.headers.get('access-control-expose-headers'), /Location/);
});
await check('CORS rejects foreign origin and unapproved headers', async () => {
    for (const headers of [
        { Origin: 'https://foreign.example', 'Access-Control-Request-Method': 'POST' },
        { Origin: 'http://localhost:3000', 'Access-Control-Request-Method': 'POST', 'Access-Control-Request-Headers': 'authorization' },
    ]) {
        const response = await request('/api/cases', { method: 'OPTIONS', headers });
        expect(response, 403); assert.equal(response.headers.get('access-control-allow-origin'), null);
    }
});

// Additional exploratory probes intentionally exercise parsing and protocol boundaries.
for (const [name, raw] of [
    ['trailing JSON object', JSON.stringify(search) + '{}'],
    ['trailing scalar', JSON.stringify(search) + ' true'],
]) await check(`strict JSON parsing: ${name}`, async () => expect(await request('/api/search', { method: 'POST', raw, headers: { 'Content-Type': 'application/json' } }, 'searchCases'), 400));
await check('observe duplicate JSON keys: last value wins (contract has no explicit policy)', async () => {
    const raw = `{"product":"${search.product}","issue":"${search.issue}","narrative":null,"narrative":"Missing transfer"}`;
    const response = await request('/api/search', { method: 'POST', raw, headers: { 'Content-Type': 'application/json' } }, 'searchCases');
    expect(response, 200); return { policy: 'Last duplicate key wins', status: response.status };
});
await check('unsupported Accept is a controlled client error', async () => {
    const response = await request('/api/health', { headers: { Accept: 'application/xml' } });
    expect(response, 406);
});
await check('integer-valued decimal limit follows OpenAPI integer semantics', async () => {
    const raw = JSON.stringify({ ...search, limit: 1 }).replace('"limit":1', '"limit":1.0');
    expect(await request('/api/search', { method: 'POST', raw, headers: { 'Content-Type': 'application/json' } }, 'searchCases'), 200);
});
await check('RFC3339 full-date rejects expanded years', async () => expect(await post('/api/cases', { ...retain, receivedDate: '+10000-01-01' }, 'retainCase'), 400));
await check('date rejects negative years', async () => expect(await post('/api/cases', { ...retain, receivedDate: '-0001-01-01' }, 'retainCase'), 400));
await check('PUT rejects expanded date before writing', async () => expect(await request(`/api/cases/${context.analyst.id}`, { method: 'PUT', body: { ...retain, receivedDate: '+10000-01-01' } }, 'replaceAnalystCase'), 400));
await check('405 response advertises supported methods in Allow', async () => {
    const response = await request('/api/health', { method: 'PATCH' }, 'getHealth');
    expect(response, 405); assert.match(response.headers.get('allow') ?? '', /GET/);
});
for (const [name, patch] of [
    ['number filename', { sourceFileName: 123 }],
    ['boolean filename', { sourceFileName: true }],
    ['date array', { periodStart: [2024, 11, 1] }],
]) await check(`metadata rejects wrong JSON type: ${name}`, async () => expect(await post('/api/imports/cfpb', form(header + row('999899999'), { ...metadata, ...patch }), 'importCfpb'), 400));

context.finalPage = expect(await request('/api/cases?size=100', {}, 'listCases'), 200);
const summary = { testedAt: new Date().toISOString(), baseUrl: base, checks: results.length, passed: results.filter(r => r.passed).length, failed: results.filter(r => !r.passed).length, httpRequests: requests.length, context, results };
await writeFile(output, JSON.stringify(summary, null, 2) + '\n');
console.log(JSON.stringify({ checks: summary.checks, passed: summary.passed, failed: summary.failed, httpRequests: summary.httpRequests, output }));
process.exitCode = summary.failed ? 1 : 0;
