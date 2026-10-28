// Concurrent imports, retention, full PUT, and search snapshot probes.
// Usage: node docs/testing/probes/concurrency.mjs <baseUrl> <dbPath> <output.json>
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { DatabaseSync } from 'node:sqlite';
import { createSession } from './lib.mjs';

const [baseUrl, dbPath, output] = process.argv.slice(2);
if (!baseUrl || !dbPath || !output) {
    throw new Error('usage: concurrency.mjs <baseUrl> <dbPath> <output.json>');
}

const session = createSession({
    name: 'concurrency',
    baseUrl,
    output,
    meta: { dbPath },
});
const db = new DatabaseSync(dbPath);
const counts = () => ({
    cases: db.prepare('SELECT COUNT(*) AS value FROM cases').get().value,
    batches: db.prepare('SELECT COUNT(*) AS value FROM import_batches').get().value,
});

const HEADER = 'Complaint ID,Product,Issue,Consumer complaint narrative,Date received\n';
const SAMPLE_META = {
    archiveUrl: 'https://files.consumerfinance.gov/f/documents/CCDB_Export_8_November_2024_through_December_2024.zip',
    sourceFileName: 'finresolve-sample.csv',
    periodStart: '2024-11-01',
    periodEnd: '2024-12-31',
};
const row = (id, narrative) => `${id},Checking or savings account,Managing an account,"${narrative}",2024-11-12\n`;

function form(csv, metadata) {
    const value = new FormData();
    value.append('file', new Blob([csv], { type: 'text/csv' }), 'concurrency.csv');
    value.append('metadata', new Blob([JSON.stringify(metadata)], { type: 'application/json' }));
    return value;
}

const RETAIN = {
    product: 'Checking or savings account',
    issue: 'Managing an account',
    narrative: 'Concurrent analyst narrative about a transfer.',
    handlingNotes: 'Concurrent create.',
    outcomeAssessment: 'UNKNOWN',
    referenceCaseIds: [],
};

await session.check(
    'CONC-09',
    'Four concurrent imports of identical bytes and metadata produce one new batch and three replays.',
    async () => {
        const sample = await readFile(new URL('../../../data/cfpb/sample.csv', import.meta.url));
        const seeded = await session.request('/api/imports/cfpb', {
            method: 'POST',
            body: form(sample, SAMPLE_META),
        });
        assert.equal(seeded.status, 201);
        assert.equal(seeded.data.imported, 500);
        const before = counts();
        const metadata = {
            archiveUrl: 'https://example.com/concurrent-identical.zip',
            sourceFileName: 'concurrent-identical.csv',
            periodStart: '2024-11-01',
            periodEnd: '2024-12-31',
        };
        const csv = HEADER + row('970000001', 'Concurrent identical import narrative.');
        const responses = await Promise.all(Array.from({ length: 4 }, () =>
            session.request('/api/imports/cfpb', { method: 'POST', body: form(csv, metadata) })));
        const statuses = responses.map((response) => response.status).sort();
        assert.deepEqual(statuses, [200, 200, 200, 201]);
        const batchIds = new Set(responses.map((response) => response.data.batchId));
        assert.equal(batchIds.size, 1);
        const imported = responses.filter((response) => response.status === 201);
        assert.equal(imported.length, 1);
        assert.equal(imported[0].data.imported, 1);
        assert.equal(counts().cases, before.cases + 1);
        assert.equal(counts().batches, before.batches + 1);
        return { statuses, batchId: [...batchIds][0], counts: counts() };
    },
);

let analystId;
await session.check(
    'CONC-10',
    'Six concurrent retention requests all succeed with distinct IDs and all rows are readable.',
    async () => {
        const before = counts();
        const responses = await Promise.all(Array.from({ length: 6 }, (_, index) =>
            session.request('/api/cases', {
                method: 'POST',
                body: { ...RETAIN, handlingNotes: `Concurrent create ${index}` },
            })));
        assert.ok(responses.every((response) => response.status === 201));
        const ids = responses.map((response) => response.data.id);
        assert.equal(new Set(ids).size, 6);
        analystId = ids[0];
        assert.equal(counts().cases, before.cases + 6);
        for (const id of ids) {
            const detail = await session.request(`/api/cases/${id}`);
            assert.equal(detail.status, 200);
            assert.ok(detail.data.analystNotes.handlingNotes.startsWith('Concurrent create '));
        }
        return { ids, counts: counts() };
    },
);

await session.check(
    'CONC-11',
    'Eight concurrent full PUTs serialize to one complete payload, and concurrent GETs never observe a mixed case.',
    async () => {
        const before = await session.request(`/api/cases/${analystId}`);
        const cfpb = await session.request('/api/cases?source=CFPB&size=2');
        const references = [cfpb.data.items[0].id];
        const payloads = Array.from({ length: 8 }, (_, index) => ({
            product: RETAIN.product,
            subProduct: 'Checking account',
            issue: RETAIN.issue,
            subIssue: null,
            narrative: `Concurrent PUT narrative ${index}.`,
            receivedDate: null,
            company: `Company ${index}`,
            companyResponse: null,
            handlingNotes: `Concurrent PUT notes ${index}`,
            outcomeAssessment: 'UNKNOWN',
            evaluationNotes: `Evaluation ${index}`,
            referenceCaseIds: index % 2 === 0 ? [] : references,
        }));
        const observed = [];
        let polling = true;
        const poller = (async () => {
            while (polling) {
                const response = await session.request(`/api/cases/${analystId}`);
                observed.push({
                    narrative: response.data.narrative,
                    company: response.data.company,
                    handlingNotes: response.data.analystNotes.handlingNotes,
                    evaluationNotes: response.data.analystNotes.evaluationNotes,
                    referenceCount: response.data.referenceCaseIds.length,
                    createdAt: response.data.createdAt,
                });
                await new Promise((resolve) => setTimeout(resolve, 10));
            }
        })();
        const responses = await Promise.all(payloads.map((body) =>
            session.request(`/api/cases/${analystId}`, { method: 'PUT', body })));
        polling = false;
        await poller;
        assert.ok(responses.every((response) => response.status === 200));
        const final = await session.request(`/api/cases/${analystId}`);
        const matching = payloads.findIndex((payload) =>
            final.data.narrative === payload.narrative
            && final.data.company === payload.company
            && final.data.analystNotes.handlingNotes === payload.handlingNotes
            && final.data.analystNotes.evaluationNotes === payload.evaluationNotes
            && JSON.stringify(final.data.referenceCaseIds) === JSON.stringify(payload.referenceCaseIds));
        assert.notEqual(matching, -1, 'final state is not one complete submitted payload');
        assert.equal(final.data.createdAt, before.data.createdAt);
        const isComplete = (state) => state.narrative === before.data.narrative
            || payloads.some((payload) =>
                state.narrative === payload.narrative
                && state.company === payload.company
                && state.handlingNotes === payload.handlingNotes
                && state.evaluationNotes === payload.evaluationNotes
                && state.referenceCount === payload.referenceCaseIds.length);
        assert.ok(
            observed.every(isComplete),
            'a concurrent GET observed a mixed or partial payload',
        );
        assert.ok(observed.every((state) => state.createdAt === before.data.createdAt));
        return { finalPayload: matching, observedReads: observed.length, final: final.data };
    },
);

await session.check(
    'CONC-12',
    'A search racing a five-case import sees only a complete before or after corpus.',
    async () => {
        const before = counts();
        const metadata = {
            archiveUrl: 'https://example.com/search-snapshot.zip',
            sourceFileName: 'search-snapshot.csv',
            periodStart: '2024-11-01',
            periodEnd: '2024-12-31',
        };
        const complaintIds = Array.from({ length: 5 }, (_, index) => String(975000001 + index));
        const csv = HEADER + complaintIds.map((id) =>
            row(id, `zzsnapshotterm zzsnapshotterm narrative ${id}.`)).join('');
        const query = {
            product: 'Checking or savings account',
            issue: 'Managing an account',
            narrative: 'zzsnapshotterm zzsnapshotterm',
            limit: 20,
        };
        const observations = [];
        const preSearch = await session.request('/api/search', { method: 'POST', body: query });
        observations.push({
            status: preSearch.status,
            corpusSize: preSearch.data.corpusSize,
            complaintIds: preSearch.data.results.map((match) => match.case.provenance?.complaintId),
        });
        let searching = true;
        const searcher = (async () => {
            while (searching) {
                const response = await session.request('/api/search', { method: 'POST', body: query });
                observations.push({
                    status: response.status,
                    corpusSize: response.data.corpusSize,
                    complaintIds: response.data.results.map((match) => match.case.provenance?.complaintId),
                });
                await new Promise((resolve) => setTimeout(resolve, 5));
            }
        })();
        await new Promise((resolve) => setTimeout(resolve, 50));
        const imported = await session.request('/api/imports/cfpb', {
            method: 'POST',
            body: form(csv, metadata),
        });
        searching = false;
        await searcher;
        assert.equal(imported.status, 201);
        assert.equal(imported.data.imported, 5);
        const postSearch = await session.request('/api/search', { method: 'POST', body: query });
        observations.push({
            status: postSearch.status,
            corpusSize: postSearch.data.corpusSize,
            complaintIds: postSearch.data.results.map((match) => match.case.provenance?.complaintId),
        });
        const after = counts();
        assert.equal(after.cases, before.cases + 5);
        const sizes = new Set(observations.map((observation) => observation.corpusSize));
        assert.deepEqual([...sizes].sort((a, b) => a - b), [before.cases, after.cases]);
        const mixed = observations.filter((observation) =>
            observation.corpusSize === before.cases
            && observation.complaintIds.some((id) => complaintIds.includes(id)));
        assert.deepEqual(mixed, []);
        const matches = observations.filter((observation) => observation.complaintIds.length > 0);
        assert.ok(matches.every((observation) => observation.corpusSize === after.cases));
        return {
            before: before.cases,
            after: after.cases,
            searches: observations.length,
            beforeCount: observations.filter((observation) => observation.corpusSize === before.cases).length,
            afterCount: observations.filter((observation) => observation.corpusSize === after.cases).length,
            mixed: mixed.length,
        };
    },
);

await session.check(
    'CONC-13',
    'Two different imports with the same complaint ID keep exactly one case and the winning provenance.',
    async () => {
        const before = counts();
        const firstCsv = HEADER + row('976000001', 'Overlapping import first narrative.');
        const secondCsv = HEADER + row('976000001', 'Overlapping import second narrative.');
        const first = session.request('/api/imports/cfpb', {
            method: 'POST',
            body: form(firstCsv, {
                archiveUrl: 'https://example.com/overlap-a.zip',
                sourceFileName: 'overlap-a.csv',
                periodStart: '2024-11-01',
                periodEnd: '2024-12-31',
            }),
        });
        const second = session.request('/api/imports/cfpb', {
            method: 'POST',
            body: form(secondCsv, {
                archiveUrl: 'https://example.com/overlap-b.zip',
                sourceFileName: 'overlap-b.csv',
                periodStart: '2024-11-01',
                periodEnd: '2024-12-31',
            }),
        });
        const [a, b] = await Promise.all([first, second]);
        assert.deepEqual([a.status, b.status], [201, 201]);
        const imported = a.data.imported + b.data.imported;
        const duplicates = a.data.duplicates + b.data.duplicates;
        assert.equal(imported, 1);
        assert.equal(duplicates, 1);
        assert.equal(counts().cases, before.cases + 1);
        assert.equal(counts().batches, before.batches + 2);
        const foundId = db.prepare(
            "SELECT id FROM cases WHERE complaint_id = '976000001'",
        ).get()?.id;
        assert.ok(foundId);
        const found = (await session.request(`/api/cases/${foundId}`)).data;
        assert.ok(
            ['Overlapping import first narrative.', 'Overlapping import second narrative.']
                .includes(found.narrative),
        );
        assert.ok(['overlap-a.csv', 'overlap-b.csv'].includes(found.provenance.sourceFileName));
        return {
            statuses: [a.status, b.status],
            imported,
            duplicates,
            narrative: found.narrative,
            provenance: found.provenance.sourceFileName,
            counts: counts(),
        };
    },
);

await session.save({ finalCounts: counts() });
db.close();
