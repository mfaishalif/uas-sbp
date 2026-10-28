// Unicode scalar/control-character integrity probes.
// Usage: node docs/testing/probes/unicode-integrity.mjs <baseUrl> <dbPath> <output.json>
import assert from 'node:assert/strict';
import { DatabaseSync } from 'node:sqlite';
import { createSession } from './lib.mjs';

const [baseUrl, dbPath, output] = process.argv.slice(2);
if (!baseUrl || !dbPath || !output) {
    throw new Error('usage: unicode-integrity.mjs <baseUrl> <dbPath> <output.json>');
}

const session = createSession({
    name: 'unicode-integrity',
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
const METADATA = {
    archiveUrl: 'https://example.com/unicode.zip',
    sourceFileName: 'unicode.csv',
    periodStart: '2024-11-01',
    periodEnd: '2024-12-31',
};
const row = (id, narrative) => `${id},Checking or savings account,Managing an account,"${narrative}",2024-11-12\n`;

function form(csv, metadata = METADATA) {
    const value = new FormData();
    value.append('file', new Blob([csv], { type: 'text/csv' }), 'unicode.csv');
    value.append('metadata', new Blob([JSON.stringify(metadata)], { type: 'application/json' }));
    return value;
}

function hasProblem(data, field, code) {
    return Array.isArray(data?.fieldErrors)
        && data.fieldErrors.some((problem) => problem.field === field && problem.code === code);
}

async function expectValidation(path, body, field, code = 'INVALID_FORMAT') {
    const before = counts();
    const response = await session.request(path, { method: 'POST', body });
    assert.equal(response.status, 400);
    assert.equal(response.data.code, 'VALIDATION_ERROR');
    assert.ok(hasProblem(response.data, field, code), `${field} must be ${code}: ${JSON.stringify(response.data)}`);
    assert.deepEqual(counts(), before);
    return {
        status: response.status,
        code: response.data.code,
        fieldErrors: response.data.fieldErrors,
        before,
        after: counts(),
    };
}

const RETAIN = {
    product: 'Checking or savings account',
    issue: 'Managing an account',
    narrative: 'Transfer narrative.',
    handlingNotes: 'Notes.',
    outcomeAssessment: 'UNKNOWN',
    referenceCaseIds: [],
};

await session.check(
    'UNI-00',
    'A valid CFPB row establishes the category catalog used by retention probes.',
    async () => {
        const before = counts();
        const response = await session.request('/api/imports/cfpb', {
            method: 'POST',
            body: form(HEADER + row('993000001', 'Seed narrative about a transfer.')),
        });
        assert.equal(response.status, 201);
        assert.equal(response.data.imported, 1);
        assert.deepEqual(counts(), { cases: before.cases + 1, batches: before.batches + 1 });
        return { cases: counts().cases, batches: counts().batches };
    },
);

for (const [id, field, value] of [
    ['UNI-01', 'narrative', '\u0000transfer'],
    ['UNI-02', 'handlingNotes', '\u0000notes'],
    ['UNI-03', 'product', '\u0000Checking or savings account'],
    ['UNI-04', 'issue', 'Managing\u0000 an account'],
    ['UNI-05', 'company', 'Company\u0000'],
    ['UNI-06', 'companyResponse', '\u0000Closed'],
    ['UNI-07', 'evaluationNotes', 'Eval\u0000notes'],
]) {
    await session.check(
        id,
        `U+0000 anywhere in ${field} is rejected before persistence with no case or batch write.`,
        async () => expectValidation('/api/cases', { ...RETAIN, [field]: value }, field),
    );
}

for (const [id, field, value] of [
    ['UNI-08', 'narrative', 'transfer\ud800account'],
    ['UNI-09', 'handlingNotes', 'notes\udfff'],
    ['UNI-10', 'company', 'company\ud800'],
    ['UNI-11', 'evaluationNotes', 'eval\udfff'],
]) {
    await session.check(
        id,
        `An unpaired UTF-16 surrogate in ${field} is rejected before persistence.`,
        async () => expectValidation('/api/cases', { ...RETAIN, [field]: value }, field),
    );
}

await session.check(
    'UNI-12',
    'Search rejects U+0000 and unpaired surrogates without changing the case or batch count.',
    async () => {
        const before = counts();
        const nul = await session.request('/api/search', {
            method: 'POST',
            body: { ...RETAIN, narrative: 'transfer\u0000account' },
        });
        const surrogate = await session.request('/api/search', {
            method: 'POST',
            body: { ...RETAIN, product: 'Checking\ud800 account' },
        });
        assert.equal(nul.status, 400);
        assert.equal(nul.data.code, 'VALIDATION_ERROR');
        assert.ok(hasProblem(nul.data, 'narrative', 'INVALID_FORMAT'));
        assert.equal(surrogate.status, 400);
        assert.equal(surrogate.data.code, 'VALIDATION_ERROR');
        assert.ok(hasProblem(surrogate.data, 'product', 'INVALID_FORMAT'));
        assert.deepEqual(counts(), before);
        return {
            nul: { status: nul.status, fieldErrors: nul.data.fieldErrors },
            surrogate: { status: surrogate.status, fieldErrors: surrogate.data.fieldErrors },
            before,
            after: counts(),
        };
    },
);

await session.check(
    'UNI-13',
    'Emoji, a valid surrogate pair, tab, and newline survive POST and GET unchanged.',
    async () => {
        const body = {
            ...RETAIN,
            narrative: 'Transfer 😀\taccount.\nSecond line about 𝄞 music.',
            handlingNotes: 'Notes 😀\tkept.\nSecond note.',
            company: 'Bank 😀',
            companyResponse: 'Closed 😀 with explanation.',
            evaluationNotes: 'Eval 𝄞\nkept.',
        };
        const created = await session.request('/api/cases', { method: 'POST', body });
        assert.equal(created.status, 201);
        const read = await session.request(`/api/cases/${created.data.id}`);
        assert.equal(read.status, 200);
        assert.equal(read.data.narrative, body.narrative);
        assert.equal(read.data.analystNotes.handlingNotes, body.handlingNotes);
        assert.equal(read.data.company, body.company);
        assert.equal(read.data.companyResponse, body.companyResponse);
        assert.equal(read.data.analystNotes.evaluationNotes, body.evaluationNotes);
        return { id: created.data.id, narrative: read.data.narrative };
    },
);

await session.check(
    'UNI-14',
    'A rejected PUT leaves every field of the prior analyst case unchanged.',
    async () => {
        const created = await session.request('/api/cases', {
            method: 'POST',
            body: { ...RETAIN, narrative: 'Original PUT narrative.', handlingNotes: 'Original notes.' },
        });
        assert.equal(created.status, 201);
        const before = await session.request(`/api/cases/${created.data.id}`);
        assert.equal(before.status, 200);
        const attempts = [
            { ...RETAIN, narrative: 'Replacement\u0000narrative.', handlingNotes: 'Replacement notes.' },
            { ...RETAIN, narrative: 'Replacement narrative.', handlingNotes: 'Replacement\ud800 notes.' },
            { ...RETAIN, narrative: 'Replacement narrative.', handlingNotes: 'Replacement notes.', evaluationNotes: 'Replacement\udfff evaluation.' },
        ];
        for (const body of attempts) {
            const response = await session.request(`/api/cases/${created.data.id}`, {
                method: 'PUT',
                body,
            });
            assert.equal(response.status, 400);
            assert.equal(response.data.code, 'VALIDATION_ERROR');
            const after = await session.request(`/api/cases/${created.data.id}`);
            assert.deepEqual(after.data, before.data);
        }
        return { id: created.data.id, fieldErrors: attempts.map(() => 'VALIDATION_ERROR') };
    },
);

await session.check(
    'UNI-15',
    'A NUL narrative row is rejected per record while the valid row in the same CSV is imported.',
    async () => {
        const before = counts();
        const csv = HEADER
            + row('993000002', 'Valid row before the malformed row.')
            + row('993000003', '\u0000NUL narrative.');
        const response = await session.request('/api/imports/cfpb', {
            method: 'POST',
            body: form(csv, { ...METADATA, sourceFileName: 'nul-row.csv' }),
        });
        assert.equal(response.status, 201);
        assert.equal(response.data.processed, 2);
        assert.equal(response.data.imported, 1);
        assert.equal(response.data.rejected, 1);
        assert.equal(response.data.duplicates, 0);
        assert.equal(response.data.errors.length, 1);
        assert.equal(response.data.errors[0].recordNumber, 2);
        assert.equal(response.data.errors[0].field, 'Consumer complaint narrative');
        assert.equal(response.data.errors[0].code, 'INVALID_FORMAT');
        assert.deepEqual(counts(), { cases: before.cases + 1, batches: before.batches + 1 });
        return { status: response.status, counts: counts(), errors: response.data.errors };
    },
);

await session.check(
    'UNI-16',
    'A NUL product row is rejected per record while the valid row in the same CSV is imported.',
    async () => {
        const before = counts();
        const csv = HEADER
            + row('993000004', 'Valid row before the malformed row.')
            + '993000005,\u0000Checking or savings account,Managing an account,"Product NUL row.",2024-11-12\n';
        const response = await session.request('/api/imports/cfpb', {
            method: 'POST',
            body: form(csv, { ...METADATA, sourceFileName: 'nul-product.csv' }),
        });
        assert.equal(response.status, 201);
        assert.equal(response.data.processed, 2);
        assert.equal(response.data.imported, 1);
        assert.equal(response.data.rejected, 1);
        assert.equal(response.data.duplicates, 0);
        assert.equal(response.data.errors[0].recordNumber, 2);
        assert.equal(response.data.errors[0].field, 'Product');
        assert.equal(response.data.errors[0].code, 'INVALID_FORMAT');
        assert.deepEqual(counts(), { cases: before.cases + 1, batches: before.batches + 1 });
        return { status: response.status, counts: counts(), errors: response.data.errors };
    },
);

await session.check(
    'UNI-17',
    'Invalid metadata text is a 400 with metadata.* field paths and no case or batch write.',
    async () => {
        const before = counts();
        const attempts = [
            [{ ...METADATA, sourceFileName: '\u0000file.csv' }, 'metadata.sourceFileName'],
            [{ ...METADATA, sourceFileName: 'sur\ud800.csv' }, 'metadata.sourceFileName'],
            [{ ...METADATA, archiveUrl: 'https://example.com/\u0000archive.zip' }, 'metadata.archiveUrl'],
        ];
        const details = [];
        for (const [metadata, field] of attempts) {
            const response = await session.request('/api/imports/cfpb', {
                method: 'POST',
                body: form(HEADER + row('993000006', 'Metadata NUL row.'), metadata),
            });
            assert.equal(response.status, 400);
            assert.equal(response.data.code, 'VALIDATION_ERROR');
            assert.ok(hasProblem(response.data, field, 'INVALID_FORMAT'));
            details.push({ status: response.status, code: response.data.code, fieldErrors: response.data.fieldErrors });
        }
        assert.deepEqual(counts(), before);
        return { before, after: counts(), details };
    },
);

await session.check(
    'UNI-18',
    'Valid Unicode metadata imports once, replays the same batch, and matches GET provenance.',
    async () => {
        const before = counts();
        const metadata = { ...METADATA, sourceFileName: 'sur-😀-𝄞.csv' };
        const csv = HEADER + row('993000007', 'Valid Unicode metadata row.');
        const first = await session.request('/api/imports/cfpb', {
            method: 'POST',
            body: form(csv, metadata),
        });
        assert.equal(first.status, 201);
        assert.equal(first.data.metadata.sourceFileName, metadata.sourceFileName);
        const replay = await session.request('/api/imports/cfpb', {
            method: 'POST',
            body: form(csv, metadata),
        });
        assert.equal(replay.status, 200);
        assert.equal(replay.data.replayed, true);
        assert.equal(replay.data.batchId, first.data.batchId);
        assert.equal(replay.data.metadata.sourceFileName, metadata.sourceFileName);
        assert.deepEqual(counts(), { cases: before.cases + 1, batches: before.batches + 1 });

        const page = await session.request('/api/cases?source=CFPB&size=100');
        let imported;
        for (const item of page.data.items) {
            const detail = await session.request(`/api/cases/${item.id}`);
            if (detail.data.provenance?.complaintId === '993000007') {
                imported = detail.data;
                break;
            }
        }
        assert.ok(imported, 'imported case was not found');
        assert.equal(imported.provenance.sourceFileName, metadata.sourceFileName);
        assert.equal(imported.provenance.importBatchId, first.data.batchId);
        return {
            first: { status: first.status, batch: first.data.batchId, source: first.data.metadata.sourceFileName },
            replay: { status: replay.status, batch: replay.data.batchId, replayed: replay.data.replayed },
            provenance: imported.provenance,
        };
    },
);

await session.save({ finalCounts: counts() });
db.close();
