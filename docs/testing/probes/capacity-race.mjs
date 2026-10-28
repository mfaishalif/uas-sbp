// Capacity boundary and the race for the 5,000th case slot.
// Usage: node docs/testing/probes/capacity-race.mjs <baseUrl> <dbPath> <output.json>
import assert from 'node:assert/strict';
import { DatabaseSync } from 'node:sqlite';
import { createSession } from './lib.mjs';

const [baseUrl, dbPath, output] = process.argv.slice(2);
if (!baseUrl || !dbPath || !output) {
    throw new Error('usage: capacity-race.mjs <baseUrl> <dbPath> <output.json>');
}

const session = createSession({
    name: 'capacity-race',
    baseUrl,
    output,
    meta: { dbPath, capacity: 5000 },
});
const db = new DatabaseSync(dbPath);
const counts = () => ({
    cases: db.prepare('SELECT COUNT(*) AS value FROM cases').get().value,
    batches: db.prepare('SELECT COUNT(*) AS value FROM import_batches').get().value,
});

const HEADER = 'Complaint ID,Product,Sub-product,Issue,Sub-issue,Consumer complaint narrative,Company,Company response to consumer,Date received\n';
const META = {
    archiveUrl: 'https://example.com/capacity.zip',
    sourceFileName: 'capacity-4998.csv',
    periodStart: '2024-11-01',
    periodEnd: '2024-12-31',
};
const row = (id, narrative) => [
    id,
    'Checking or savings account',
    'Checking account',
    'Managing an account',
    'Deposits and withdrawals',
    `"${narrative}"`,
    'Example Bank',
    'Closed with explanation',
    '2024-11-12',
].join(',') + '\n';

function form(csv, metadata) {
    const value = new FormData();
    value.append('file', new Blob([csv], { type: 'text/csv' }), 'capacity.csv');
    value.append('metadata', new Blob([JSON.stringify(metadata)], { type: 'application/json' }));
    return value;
}

const fillRows = Array.from({ length: 4998 }, (_, index) =>
    row(String(950000000 + index), `Synthetic capacity narrative ${index} about a transfer.`));
const fillCsv = HEADER + fillRows.join('');
let fillBatch;
let analyst;

await session.check(
    'CONC-01',
    'A 4,998-row import is accepted atomically and leaves the database at 4,998 cases.',
    async () => {
        const response = await session.request('/api/imports/cfpb', {
            method: 'POST',
            body: form(fillCsv, META),
        });
        assert.equal(response.status, 201);
        assert.equal(response.data.processed, 4998);
        assert.equal(response.data.imported, 4998);
        assert.equal(response.data.duplicates, 0);
        assert.equal(response.data.rejected, 0);
        fillBatch = response.data.batchId;
        assert.deepEqual(counts(), { cases: 4998, batches: 1 });
        return { batchId: fillBatch, counts: counts() };
    },
);

await session.check(
    'CONC-02',
    'A normal retention takes the 4,999th slot before the race.',
    async () => {
        const response = await session.request('/api/cases', {
            method: 'POST',
            body: {
                product: 'Checking or savings account',
                subProduct: 'Checking account',
                issue: 'Managing an account',
                subIssue: 'Deposits and withdrawals',
                narrative: 'Retention before capacity race.',
                handlingNotes: 'Pre-race fixture.',
                outcomeAssessment: 'UNKNOWN',
                referenceCaseIds: [],
            },
        });
        assert.equal(response.status, 201);
        analyst = response.data;
        assert.equal(counts().cases, 4999);
        return { id: analyst.id, counts: counts() };
    },
);

let race;
await session.check(
    'CONC-03',
    'Concurrent import and retention racing for the 5,000th slot produce exactly one winner and never exceed 5,000.',
    async () => {
        const raceCsv = HEADER + row('960000001', 'Race slot narrative about a transfer.');
        const raceMeta = { ...META, sourceFileName: 'capacity-race.csv' };
        const [importResponse, retainResponse] = await Promise.all([
            session.request('/api/imports/cfpb', {
                method: 'POST',
                body: form(raceCsv, raceMeta),
            }),
            session.request('/api/cases', {
                method: 'POST',
                body: {
                    product: 'Checking or savings account',
                    subProduct: 'Checking account',
                    issue: 'Managing an account',
                    subIssue: 'Deposits and withdrawals',
                    narrative: 'Retention race for the final slot.',
                    handlingNotes: 'Race retention.',
                    outcomeAssessment: 'UNKNOWN',
                    referenceCaseIds: [],
                },
            }),
        ]);
        race = { importResponse, retainResponse, counts: counts() };
        assert.deepEqual(
            [importResponse.status, retainResponse.status].sort(),
            [201, 409],
        );
        assert.equal(race.counts.cases, 5000);
        assert.ok(race.counts.cases <= 5000);
        const importWon = importResponse.status === 201;
        const retainWon = retainResponse.status === 201;
        assert.equal(importWon !== retainWon, true);
        const raceRow = db.prepare(
            "SELECT COUNT(*) AS value FROM cases WHERE complaint_id = '960000001'",
        ).get().value;
        const raceBatch = db.prepare(
            "SELECT COUNT(*) AS value FROM import_batches WHERE source_file_name = 'capacity-race.csv'",
        ).get().value;
        if (importWon) {
            assert.equal(raceRow, 1);
            assert.equal(raceBatch, 1);
        } else {
            assert.equal(raceRow, 0);
            assert.equal(raceBatch, 0);
            assert.equal(retainResponse.data.analystNotes.handlingNotes, 'Race retention.');
        }
        return {
            statuses: { import: importResponse.status, retention: retainResponse.status },
            winner: importWon ? 'import' : 'retention',
            loserCode: importWon ? retainResponse.data.code : importResponse.data.code,
            raceRow,
            raceBatch,
            counts: race.counts,
        };
    },
);

await session.check(
    'CONC-04',
    'At 5,000, new import and new retention are both rejected with 409 and do not add rows or batches.',
    async () => {
        const before = counts();
        const importResponse = await session.request('/api/imports/cfpb', {
            method: 'POST',
            body: form(
                HEADER + row('960000002', 'Post-capacity import narrative.'),
                { ...META, sourceFileName: 'post-capacity.csv' },
            ),
        });
        const retainResponse = await session.request('/api/cases', {
            method: 'POST',
            body: {
                product: 'Checking or savings account',
                subProduct: 'Checking account',
                issue: 'Managing an account',
                subIssue: 'Deposits and withdrawals',
                narrative: 'Post-capacity retention narrative.',
                handlingNotes: 'Post-capacity retention.',
                outcomeAssessment: 'UNKNOWN',
                referenceCaseIds: [],
            },
        });
        assert.equal(importResponse.status, 409);
        assert.equal(importResponse.data.code, 'KNOWLEDGE_BASE_LIMIT_EXCEEDED');
        assert.equal(retainResponse.status, 409);
        assert.equal(retainResponse.data.code, 'KNOWLEDGE_BASE_LIMIT_EXCEEDED');
        assert.deepEqual(counts(), before);
        return { statuses: [importResponse.status, retainResponse.status], counts: counts() };
    },
);

await session.check(
    'CONC-05',
    'Replaying the original 4,998-row batch at 5,000 returns 200 and does not add cases.',
    async () => {
        const before = counts();
        const response = await session.request('/api/imports/cfpb', {
            method: 'POST',
            body: form(fillCsv, META),
        });
        assert.equal(response.status, 200);
        assert.equal(response.data.replayed, true);
        assert.equal(response.data.batchId, fillBatch);
        assert.deepEqual(counts(), before);
        return { replayed: true, batchId: fillBatch };
    },
);

await session.check(
    'CONC-06',
    'A duplicate-only import at 5,000 is accepted as a report batch and adds no cases.',
    async () => {
        const before = counts();
        const response = await session.request('/api/imports/cfpb', {
            method: 'POST',
            body: form(fillCsv, { ...META, sourceFileName: 'capacity-copy.csv' }),
        });
        assert.equal(response.status, 201);
        assert.equal(response.data.replayed, false);
        assert.equal(response.data.imported, 0);
        assert.equal(response.data.duplicates, 4998);
        assert.equal(response.data.rejected, 0);
        assert.equal(counts().cases, before.cases);
        assert.equal(counts().batches, before.batches + 1);
        return { counts: counts(), imported: 0, duplicates: 4998 };
    },
);

await session.check(
    'CONC-07',
    'A full PUT of an existing analyst case still works at 5,000 and preserves createdAt.',
    async () => {
        const detail = await session.request(`/api/cases/${analyst.id}`);
        const body = {
            product: detail.data.product,
            subProduct: detail.data.subProduct,
            issue: detail.data.issue,
            subIssue: detail.data.subIssue,
            narrative: detail.data.narrative,
            receivedDate: detail.data.receivedDate,
            company: 'Updated at capacity',
            companyResponse: null,
            handlingNotes: 'PUT at capacity.',
            outcomeAssessment: 'RESOLVED',
            evaluationNotes: null,
            referenceCaseIds: [],
        };
        const response = await session.request(`/api/cases/${analyst.id}`, {
            method: 'PUT',
            body,
        });
        assert.equal(response.status, 200);
        assert.equal(response.data.createdAt, analyst.createdAt);
        assert.equal(response.data.company, 'Updated at capacity');
        assert.equal(counts().cases, 5000);
        return { id: analyst.id, updatedAt: response.data.updatedAt, counts: counts() };
    },
);

await session.check(
    'CONC-08',
    'Search at 5,000 reports corpusSize 5000 and stays within the requested limit.',
    async () => {
        const response = await session.request('/api/search', {
            method: 'POST',
            body: {
                product: 'Checking or savings account',
                subProduct: 'Checking account',
                issue: 'Managing an account',
                subIssue: 'Deposits and withdrawals',
                narrative: 'Synthetic capacity narrative 0 about a transfer.',
                limit: 20,
            },
        });
        assert.equal(response.status, 200);
        assert.equal(response.data.corpusSize, 5000);
        assert.ok(response.data.returnedCount <= 20);
        assert.ok(response.data.results.every((match) => match.score >= 0.5));
        return {
            status: response.data.status,
            returnedCount: response.data.returnedCount,
            corpusSize: response.data.corpusSize,
        };
    },
);

await session.save({ finalCounts: counts(), raceStatuses: race ? {
    import: race.importResponse.status,
    retention: race.retainResponse.status,
} : null });
db.close();
