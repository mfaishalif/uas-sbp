// Restart persistence and controlled SQLite write-lock probes.
// Usage:
//   node persistence.mjs seed   <baseUrl> <stateOut>
//   node persistence.mjs verify <baseUrl> <stateIn> <resultOut>
//   node persistence.mjs busy   <baseUrl> <dbPath> <resultOut>
import assert from 'node:assert/strict';
import { readFile, writeFile } from 'node:fs/promises';
import { DatabaseSync } from 'node:sqlite';
import { createSession } from './lib.mjs';

const [mode, baseUrl, third, fourth] = process.argv.slice(2);
if (!mode || !baseUrl) {
    throw new Error('usage: persistence.mjs seed|verify|busy <baseUrl> ...');
}

const HEADER = 'Complaint ID,Product,Issue,Consumer complaint narrative,Date received\n';
const SAMPLE_META = {
    archiveUrl: 'https://files.consumerfinance.gov/f/documents/CCDB_Export_8_November_2024_through_December_2024.zip',
    sourceFileName: 'finresolve-sample.csv',
    periodStart: '2024-11-01',
    periodEnd: '2024-12-31',
};
const RETAIN = {
    product: 'Checking or savings account',
    subProduct: 'Checking account',
    issue: 'Managing an account',
    subIssue: 'Deposits and withdrawals',
    narrative: 'Persistent analyst narrative about a missing transfer.',
    receivedDate: '2024-12-01',
    company: 'Example Bank',
    companyResponse: 'Closed with explanation',
    handlingNotes: 'Initial notes.',
    outcomeAssessment: 'UNKNOWN',
    evaluationNotes: 'Initial evaluation.',
    referenceCaseIds: [],
};

async function json(path, init) {
    const response = await fetch(baseUrl + path, init);
    const text = await response.text();
    let body;
    try {
        body = text ? JSON.parse(text) : null;
    } catch {
        body = text;
    }
    return { status: response.status, headers: response.headers, body };
}

async function formImport(csv, metadata) {
    const value = new FormData();
    value.append('file', new Blob([csv], { type: 'text/csv' }), 'persistence.csv');
    value.append('metadata', new Blob([JSON.stringify(metadata)], { type: 'application/json' }));
    return json('/api/imports/cfpb', { method: 'POST', body: value });
}

async function capture() {
    const state = {};
    state.health = await json('/api/health');
    state.categories = await json('/api/categories');
    const pages = [];
    const caseIds = [];
    for (let page = 0; ; page += 1) {
        const response = await json(`/api/cases?page=${page}&size=100`);
        pages.push(response.body);
        caseIds.push(...response.body.items.map((item) => item.id));
        if (!response.body.hasNext) break;
    }
    state.pages = pages;
    state.caseIds = caseIds;
    state.details = {};
    const analyst = pages.flatMap((page) => page.items).find((item) => item.source === 'ANALYST');
    const cfpb = pages.flatMap((page) => page.items).filter((item) => item.source === 'CFPB');
    for (const id of [analyst?.id, ...cfpb.slice(0, 3).map((item) => item.id)].filter(Boolean)) {
        state.details[id] = await json(`/api/cases/${id}`);
    }
    state.replay = await formImport(
        await readFile(new URL('../../../data/cfpb/sample.csv', import.meta.url)),
        SAMPLE_META,
    );
    state.replayMetadata = SAMPLE_META;
    state.analystId = analyst?.id;
    return state;
}

function normalizeState(state) {
    return {
        health: state.health.body,
        categories: state.categories.body,
        pages: state.pages.map((page) => ({
            page: page.page,
            size: page.size,
            totalElements: page.totalElements,
            totalPages: page.totalPages,
            hasNext: page.hasNext,
            items: page.items,
        })),
        caseIds: state.caseIds,
        details: Object.fromEntries(Object.entries(state.details).map(([id, detail]) => [id, detail.body])),
        replayStatus: state.replay.status,
        replay: { status: state.replay.status, body: state.replay.body },
        replayMetadata: state.replayMetadata,
    };
}

if (mode === 'seed') {
    const [stateOut] = [third];
    if (!stateOut) throw new Error('seed requires <stateOut>');
    const sample = await readFile(new URL('../../../data/cfpb/sample.csv', import.meta.url));
    const imported = await formImport(sample, SAMPLE_META);
    if (imported.status !== 201) throw new Error(`sample import failed: ${JSON.stringify(imported.body)}`);
    const page = await json('/api/cases?source=CFPB&size=3');
    const references = page.body.items.slice(0, 2).map((item) => item.id);
    const created = await json('/api/cases', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ ...RETAIN, referenceCaseIds: references }),
    });
    if (created.status !== 201) throw new Error(`retention failed: ${JSON.stringify(created.body)}`);
    const replacement = {
        ...RETAIN,
        narrative: 'Updated persistent narrative about a wire transfer.',
        company: 'Updated Bank',
        companyResponse: null,
        handlingNotes: 'Updated notes.',
        outcomeAssessment: 'RESOLVED',
        evaluationNotes: null,
        referenceCaseIds: [references[1]],
    };
    const updated = await json(`/api/cases/${created.body.id}`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(replacement),
    });
    if (updated.status !== 200) throw new Error(`PUT failed: ${JSON.stringify(updated.body)}`);
    const state = await capture();
    await writeFile(stateOut, `${JSON.stringify(normalizeState(state), null, 2)}\n`);
    console.log(JSON.stringify({
        stateOut,
        analystId: created.body.id,
        cases: state.caseIds.length,
        replayStatus: state.replay.status,
        replayBatch: state.replay.body.batchId,
    }));
} else if (mode === 'verify') {
    const [stateIn, resultOut] = [third, fourth];
    if (!stateIn || !resultOut) throw new Error('verify requires <stateIn> <resultOut>');
    const before = JSON.parse(await readFile(stateIn, 'utf8'));
    const after = normalizeState(await capture());
    const checks = [
        ['health', before.health, after.health],
        ['categories', before.categories, after.categories],
        ['pages', before.pages, after.pages],
        ['caseIds', before.caseIds, after.caseIds],
        ['details', before.details, after.details],
        ['replayMetadata', before.replayMetadata, after.replayMetadata],
    ];
    const mismatches = [];
    for (const [name, expected, actual] of checks) {
        try {
            assert.deepEqual(actual, expected);
        } catch {
            mismatches.push(name);
        }
    }
    const replayMatches = after.replay?.status === 200 && after.replay?.body?.replayed === true;
    const result = {
        session: 'persistence-restart',
        checkedAt: new Date().toISOString(),
        baseUrl,
        result: mismatches.length === 0 && replayMatches ? 'PASS' : 'FAIL',
        mismatches,
        comparisons: checks.map(([name]) => name),
        replay: {
            status: after.replay?.status,
            replayed: after.replay?.body?.replayed,
            batchId: after.replay?.body?.batchId,
        },
        analystId: before.analystId,
        caseCount: after.caseIds.length,
        detailIds: Object.keys(after.details),
    };
    await writeFile(resultOut, `${JSON.stringify(result, null, 2)}\n`);
    console.log(JSON.stringify(result));
    if (result.result === 'FAIL') process.exitCode = 1;
} else if (mode === 'busy') {
    const [dbPath, resultOut] = [third, fourth];
    if (!dbPath || !resultOut) throw new Error('busy requires <dbPath> <resultOut>');
    const session = createSession({
        name: 'persistence-busy',
        baseUrl,
        output: resultOut,
        meta: { dbPath },
    });
    const db = new DatabaseSync(dbPath);
    const counts = () => ({
        cases: db.prepare('SELECT COUNT(*) AS value FROM cases').get().value,
        batches: db.prepare('SELECT COUNT(*) AS value FROM import_batches').get().value,
    });
    const analystPage = await session.request('/api/cases?source=ANALYST');
    const analystId = analystPage.data.items[0].id;
    const beforeDetail = await session.request(`/api/cases/${analystId}`);
    const before = counts();
    const putBody = {
        product: beforeDetail.data.product,
        subProduct: beforeDetail.data.subProduct,
        issue: beforeDetail.data.issue,
        subIssue: beforeDetail.data.subIssue,
        narrative: beforeDetail.data.narrative,
        receivedDate: beforeDetail.data.receivedDate,
        company: beforeDetail.data.company,
        companyResponse: beforeDetail.data.companyResponse,
        handlingNotes: 'Locked PUT must not persist',
        outcomeAssessment: 'UNRESOLVED',
        evaluationNotes: null,
        referenceCaseIds: [],
    };
    const unique = String(Date.now());
    const csv = HEADER + `${unique},Checking or savings account,Managing an account,"Locked import narrative.",2024-11-12\n`;
    const metadata = {
        archiveUrl: `https://example.com/locked-${unique}.zip`,
        sourceFileName: `locked-${unique}.csv`,
        periodStart: '2024-11-01',
        periodEnd: '2024-12-31',
    };

    db.exec('PRAGMA busy_timeout = 0');
    db.exec('BEGIN IMMEDIATE');
    let locked = true;
    try {
        const calls = await Promise.all([
            (async () => {
                const started = performance.now();
                const response = await session.request(`/api/cases/${analystId}`, { method: 'PUT', body: putBody });
                return { name: 'PUT', status: response.status, code: response.data.code, ms: Math.round(performance.now() - started) };
            })(),
            (async () => {
                const started = performance.now();
                const response = await session.request('/api/cases', {
                    method: 'POST',
                    body: { ...putBody, narrative: 'Locked POST narrative.', handlingNotes: 'Locked POST' },
                });
                return { name: 'POST', status: response.status, code: response.data.code, ms: Math.round(performance.now() - started) };
            })(),
            (async () => {
                const started = performance.now();
                const response = await session.request('/api/imports/cfpb', {
                    method: 'POST',
                    body: (() => {
                        const value = new FormData();
                        value.append('file', new Blob([csv], { type: 'text/csv' }), 'locked.csv');
                        value.append('metadata', new Blob([JSON.stringify(metadata)], { type: 'application/json' }));
                        return value;
                    })(),
                });
                return { name: 'import', status: response.status, code: response.data.code, ms: Math.round(performance.now() - started) };
            })(),
            (async () => {
                const started = performance.now();
                const response = await session.request('/api/health');
                return { name: 'health', status: response.status, body: response.data, ms: Math.round(performance.now() - started) };
            })(),
            (async () => {
                const started = performance.now();
                const response = await session.request('/api/cases?source=ANALYST');
                return { name: 'read', status: response.status, total: response.data.totalElements, ms: Math.round(performance.now() - started) };
            })(),
        ]);
        const after = counts();
        db.exec('ROLLBACK');
        locked = false;
        const during = await session.request(`/api/cases/${analystId}`);
        const retryPut = await session.request(`/api/cases/${analystId}`, { method: 'PUT', body: putBody });
        const retryImport = await session.request('/api/imports/cfpb', {
            method: 'POST',
            body: (() => {
                const value = new FormData();
                value.append('file', new Blob([csv], { type: 'text/csv' }), 'locked.csv');
                value.append('metadata', new Blob([JSON.stringify(metadata)], { type: 'application/json' }));
                return value;
            })(),
        });
        const final = counts();
        db.close();
        const writes = calls.filter((call) => ['PUT', 'POST', 'import'].includes(call.name));
        const failures = [];
        for (const call of writes) {
            if (call.status !== 503 || call.code !== 'DATABASE_BUSY') failures.push(call);
        }
        if (JSON.stringify(after) !== JSON.stringify(before)) failures.push({ name: 'atomicity', before, after });
        if (during.data.analystNotes.handlingNotes !== beforeDetail.data.analystNotes.handlingNotes) {
            failures.push({ name: 'PUT persisted while locked', detail: during.data.analystNotes });
        }
        if (retryPut.status !== 200) failures.push({ name: 'PUT retry', status: retryPut.status });
        if (retryImport.status !== 201) failures.push({ name: 'import retry', status: retryImport.status });
        const result = {
            result: failures.length === 0 ? 'PASS' : 'FAIL',
            failures,
            lockCalls: calls,
            counts: { before, during: after, final },
            healthWhileLocked: calls.find((call) => call.name === 'health'),
            readWhileLocked: calls.find((call) => call.name === 'read'),
            retry: {
                put: retryPut.status,
                import: retryImport.status,
                importBatch: retryImport.data?.batchId,
            },
            putUnchangedWhileLocked: during.data.updatedAt === beforeDetail.data.updatedAt,
            note: '503 latency is observed, not an SLA; reads remain available in WAL mode.',
        };
        session.results.push({
            id: 'PERS-05',
            hypothesis: 'A controlled SQLite write lock produces 503 DATABASE_BUSY for writes, leaves reads and data intact, and recovers after release.',
            result: result.result,
            durationMs: 0,
            detail: {
                failures: result.failures,
                lockCalls: result.lockCalls,
                counts: result.counts,
                retry: result.retry,
                putUnchangedWhileLocked: result.putUnchangedWhileLocked,
            },
            requests: session.requests.slice(-10),
        });
        await session.save(result);
    } finally {
        if (locked) {
            try { db.exec('ROLLBACK'); } catch { /* already rolled back */ }
            db.close();
        }
    }
} else {
    throw new Error(`unknown mode: ${mode}`);
}
