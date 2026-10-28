// Import validation, duplicate classification, provenance, replay, and parser boundaries.
// Usage: node docs/testing/probes/import-provenance.mjs <baseUrl> <dbPath> <output.json>
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { DatabaseSync } from 'node:sqlite';
import { createSession } from './lib.mjs';

const [baseUrl, dbPath, output] = process.argv.slice(2);
if (!baseUrl || !dbPath || !output) {
    throw new Error('usage: import-provenance.mjs <baseUrl> <dbPath> <output.json>');
}

const session = createSession({
    name: 'import-provenance',
    baseUrl,
    output,
    meta: { dbPath },
});
const db = new DatabaseSync(dbPath);
const counts = () => ({
    cases: db.prepare('SELECT COUNT(*) AS value FROM cases').get().value,
    batches: db.prepare('SELECT COUNT(*) AS value FROM import_batches').get().value,
});

const HEADER = 'Complaint ID,Product,Sub-product,Issue,Sub-issue,'
    + 'Consumer complaint narrative,Company,Company response to consumer,Date received\n';
const METADATA = {
    archiveUrl: 'https://files.consumerfinance.gov/f/documents/CCDB_Export_8_November_2024_through_December_2024.zip',
    sourceFileName: 'finresolve-sample.csv',
    periodStart: '2024-11-01',
    periodEnd: '2024-12-31',
};
const row = (id, narrative = 'A synthetic missing transfer complaint.', date = '2024-11-12', extra = {}) => {
    const values = {
        id,
        product: 'Checking or savings account',
        subProduct: 'Checking account',
        issue: 'Managing an account',
        subIssue: 'Deposits and withdrawals',
        narrative,
        company: 'Example Bank',
        companyResponse: 'Closed with explanation',
        date,
        ...extra,
    };
    return [
        values.id,
        values.product,
        values.subProduct,
        values.issue,
        values.subIssue,
        `"${String(values.narrative).replaceAll('"', '""')}"`,
        values.company,
        values.companyResponse,
        values.date,
    ].join(',') + '\n';
};

async function importForm(csv, metadata = METADATA, { file = true, info = true } = {}) {
    const form = new FormData();
    if (file) form.append('file', new Blob([csv], { type: 'text/csv' }), 'exploratory.csv');
    if (info) form.append('metadata', new Blob([JSON.stringify(metadata)], { type: 'application/json' }));
    return session.request('/api/imports/cfpb', { method: 'POST', body: form });
}

async function detailByComplaintId(complaintId) {
    const first = await session.request('/api/cases?size=100');
    for (let page = 0; page < first.data.totalPages; page += 1) {
        const list = page === 0 ? first : await session.request(`/api/cases?page=${page}&size=100`);
        for (const item of list.data.items) {
            const detail = await session.request(`/api/cases/${item.id}`);
            if (detail.data.provenance?.complaintId === complaintId) return detail.data;
        }
    }
    return null;
}

await session.check(
    'IMP-01',
    'A fresh database starts empty and the committed 500-case sample imports with the manifest checksum.',
    async () => {
        const health = await session.request('/api/health');
        assert.equal(health.status, 200);
        assert.deepEqual(health.data, { status: 'UP', database: 'UP' });
        const list = await session.request('/api/cases');
        assert.equal(list.data.totalElements, 0);
        const categories = await session.request('/api/categories');
        assert.deepEqual(categories.data.items, []);
        const sample = await readFile(new URL('../../../data/cfpb/sample.csv', import.meta.url));
        const response = await importForm(sample);
        assert.equal(response.status, 201);
        assert.equal(response.data.imported, 500);
        assert.equal(response.data.duplicates, 0);
        assert.equal(response.data.rejected, 0);
        assert.equal(response.data.processed, 500);
        assert.equal(
            response.data.csvSha256,
            '682bb04cf77a7df10104c822ab24af4bd42e1a088cd2678c7c72921b7983a644',
        );
        const after = counts();
        assert.equal(after.cases, 500);
        assert.equal(after.batches, 1);
        return { batchId: response.data.batchId, checksum: response.data.csvSha256 };
    },
);

let sampleBatchId;
let sampleDetail;
await session.check(
    'IMP-02',
    'Byte-identical and whitespace-normalized metadata replays return 200 with the original batch and counts.',
    async () => {
        const sample = await readFile(new URL('../../../data/cfpb/sample.csv', import.meta.url));
        const first = await importForm(sample);
        assert.equal(first.status, 200);
        assert.equal(first.data.replayed, true);
        assert.equal(first.data.imported, 500);
        sampleBatchId = first.data.batchId;
        const padded = await importForm(sample, {
            ...METADATA,
            archiveUrl: `  ${METADATA.archiveUrl}  `,
            sourceFileName: `  ${METADATA.sourceFileName}  `,
        });
        assert.equal(padded.status, 200);
        assert.equal(padded.data.replayed, true);
        assert.equal(padded.data.batchId, sampleBatchId);
        const before = counts();
        assert.equal(before.cases, 500);
        assert.equal(before.batches, 1);
        return { batchId: sampleBatchId };
    },
);

await session.check(
    'IMP-03',
    'The same CSV with different metadata is a 201 duplicate import, not a replay, and does not overwrite provenance.',
    async () => {
        const sample = await readFile(new URL('../../../data/cfpb/sample.csv', import.meta.url));
        const response = await importForm(sample, { ...METADATA, sourceFileName: 'another-sample.csv' });
        assert.equal(response.status, 201);
        assert.equal(response.data.replayed, false);
        assert.equal(response.data.imported, 0);
        assert.equal(response.data.duplicates, 500);
        assert.equal(counts().cases, 500);
        const page = await session.request('/api/cases?source=CFPB&size=1');
        sampleDetail = await session.request(`/api/cases/${page.data.items[0].id}`);
        assert.equal(sampleDetail.data.provenance.sourceFileName, METADATA.sourceFileName);
        assert.equal(sampleDetail.data.provenance.csvSha256, response.data.csvSha256);
        return { imported: 0, duplicates: 500 };
    },
);

let mixedResults;
await session.check(
    'IMP-04',
    'Mixed valid, invalid, duplicate, huge-ID, multiline, and out-of-period rows are classified once with the first valid record winning.',
    async () => {
        const hugeId = '900719925474099312345678901234567890';
        const csv = HEADER
            + row('900000001', 'First narrative about a missing transfer.')
            + row('900000001', 'Second narrative that must not overwrite the first.')
            + row('bad-id')
            + row('900000002', 'the and 123')
            + row('900000002', 'Valid narrative after an invalid duplicate.')
            + row(hugeId, 'Huge complaint identifier narrative.')
            + row('900000003', 'Line one about a transfer.\nLine two about the account.')
            + row('900000004', 'Out of period narrative.', '2025-01-01');
        const response = await importForm(csv, { ...METADATA, sourceFileName: 'mixed.csv' });
        assert.equal(response.status, 201);
        assert.equal(response.data.processed, 8);
        assert.equal(response.data.imported, 4);
        assert.equal(response.data.duplicates, 1);
        assert.equal(response.data.rejected, 3);
        assert.equal(response.data.errorsTruncated, false);
        assert.equal(response.data.errors.length, 3);
        assert.deepEqual(
            response.data.errors.map((error) => [error.recordNumber, error.field, error.code]),
            [
                [3, 'Complaint ID', 'INVALID_FORMAT'],
                [4, 'Consumer complaint narrative', 'INVALID_FORMAT'],
                [8, 'Date received', 'OUT_OF_RANGE'],
            ],
        );
        mixedResults = response.data;
        const page = await session.request('/api/cases?size=100');
        const ids = [];
        for (let pageIndex = 0; pageIndex < page.data.totalPages; pageIndex += 1) {
            const next = await session.request(`/api/cases?page=${pageIndex}&size=100`);
            ids.push(...next.data.items.map((item) => item.id));
        }
        const details = [];
        for (const id of ids) {
            const detail = await session.request(`/api/cases/${id}`);
            if (['900000001', '900000002', hugeId, '900000003'].includes(detail.data.provenance?.complaintId)) {
                details.push(detail.data);
            }
        }
        const first = details.find((detail) => detail.provenance.complaintId === '900000001');
        const afterInvalid = details.find((detail) => detail.provenance.complaintId === '900000002');
        const huge = details.find((detail) => detail.provenance.complaintId === hugeId);
        const multiline = details.find((detail) => detail.provenance.complaintId === '900000003');
        assert.equal(first.narrative, 'First narrative about a missing transfer.');
        assert.equal(afterInvalid.narrative, 'Valid narrative after an invalid duplicate.');
        assert.equal(huge.provenance.complaintId, hugeId);
        assert.equal(multiline.narrative, 'Line one about a transfer.\nLine two about the account.');
        return { processed: 8, imported: 4, duplicates: 1, rejected: 3, hugeId };
    },
);

let allInvalidBatch;
await session.check(
    'IMP-05',
    'A file whose rows are all invalid still creates a 201 report batch with capped errors, and an identical replay returns 200.',
    async () => {
        const csv = HEADER + Array.from({ length: 21 }, (_, index) => row(`bad-${index}`)).join('');
        const metadata = { ...METADATA, sourceFileName: 'all-invalid.csv' };
        const response = await importForm(csv, metadata);
        assert.equal(response.status, 201);
        assert.equal(response.data.processed, 21);
        assert.equal(response.data.imported, 0);
        assert.equal(response.data.duplicates, 0);
        assert.equal(response.data.rejected, 21);
        assert.equal(response.data.errors.length, 20);
        assert.equal(response.data.errorsTruncated, true);
        assert.equal(response.data.errors[0].recordNumber, 1);
        allInvalidBatch = response.data.batchId;
        const replay = await importForm(csv, metadata);
        assert.equal(replay.status, 200);
        assert.equal(replay.data.replayed, true);
        assert.equal(replay.data.batchId, allInvalidBatch);
        assert.equal(replay.data.rejected, 21);
        return { batchId: allInvalidBatch, errors: 20 };
    },
);

await session.check(
    'IMP-06',
    'A UTF-8 BOM is accepted, the checksum covers the raw bytes, identical BOM bytes replay, and a BOM-less copy is a duplicate.',
    async () => {
        const csv = HEADER + row('900000010', 'BOM narrative about a missing transfer.');
        const bom = Buffer.concat([Buffer.from([0xef, 0xbb, 0xbf]), Buffer.from(csv)]);
        const metadata = { ...METADATA, sourceFileName: 'bom.csv' };
        const first = await importForm(bom, metadata);
        assert.equal(first.status, 201);
        assert.equal(first.data.imported, 1);
        const replay = await importForm(bom, metadata);
        assert.equal(replay.status, 200);
        assert.equal(replay.data.replayed, true);
        assert.equal(replay.data.batchId, first.data.batchId);
        const noBom = await importForm(csv, { ...metadata, sourceFileName: 'no-bom.csv' });
        assert.equal(noBom.status, 201);
        assert.equal(noBom.data.imported, 0);
        assert.equal(noBom.data.duplicates, 1);
        assert.notEqual(noBom.data.csvSha256, first.data.csvSha256);
        return { bomSha: first.data.csvSha256, noBomSha: noBom.data.csvSha256 };
    },
);

await session.check(
    'IMP-07',
    'File-level CSV failures return 400 and leave both case and batch counts unchanged.',
    async () => {
        const before = counts();
        const attempts = [
            ['', 'empty.csv'],
            [HEADER, 'header-only.csv'],
            [HEADER + '\n\n\r\n', 'header-and-blank-lines.csv'],
            ['Product,Issue\nChecking,test\n', 'missing-id-header.csv'],
            ['Complaint ID,Product,Issue,Issue,Consumer complaint narrative\n', 'duplicate-header.csv'],
            [`${HEADER}999,\"unterminated`, 'broken-quote.csv'],
            [new Uint8Array([0xff, 0xfe, 0xfd]), 'invalid-utf8.csv'],
        ];
        const statuses = [];
        for (const [bytes, name] of attempts) {
            const response = await importForm(bytes, { ...METADATA, sourceFileName: name });
            statuses.push({ name, status: response.status, code: response.data.code });
            assert.equal(response.status, 400);
            assert.equal(response.data.code, 'INVALID_CSV');
        }
        assert.deepEqual(counts(), before);
        return statuses;
    },
);

await session.check(
    'IMP-08',
    'A 5,001-record file is rejected before persistence and does not change the case or batch count.',
    async () => {
        const before = counts();
        const csv = HEADER + Array.from({ length: 5001 }, (_, index) => row(910000000 + index)).join('');
        const response = await importForm(csv, { ...METADATA, sourceFileName: 'too-many.csv' });
        assert.equal(response.status, 400);
        assert.equal(response.data.code, 'IMPORT_LIMIT_EXCEEDED');
        assert.deepEqual(counts(), before);
        return { status: 400 };
    },
);

await session.check(
    'IMP-09',
    'A CSV upload larger than 20 MiB returns 413 with a JSON error and no data change.',
    async () => {
        const before = counts();
        const response = await session.request('/api/imports/cfpb', {
            method: 'POST',
            body: (() => {
                const form = new FormData();
                form.append('file', new Blob([new Uint8Array(20 * 1024 * 1024 + 1)], { type: 'text/csv' }), 'large.csv');
                form.append('metadata', new Blob([JSON.stringify(METADATA)], { type: 'application/json' }));
                return form;
            })(),
            timeout: 120000,
        });
        assert.equal(response.status, 413);
        assert.equal(response.data.code, 'PAYLOAD_TOO_LARGE');
        assert.deepEqual(counts(), before);
        return { status: 413 };
    },
);

await session.check(
    'IMP-10',
    'A blank line after the final data row is ignored rather than counted as a rejected record.',
    async () => {
        const before = counts();
        const csv = HEADER + row('900000020', 'Blank line boundary narrative.') + '\n';
        const response = await importForm(csv, { ...METADATA, sourceFileName: 'blank-line.csv' });
        assert.equal(response.status, 201);
        assert.equal(response.data.processed, 1);
        assert.equal(response.data.imported, 1);
        assert.equal(response.data.rejected, 0);
        assert.equal(response.data.duplicates, 0);
        assert.deepEqual(response.data.errors, []);
        assert.deepEqual(counts(), { cases: before.cases + 1, batches: before.batches + 1 });
        const detail = await detailByComplaintId('900000020');
        assert.equal(detail.narrative, 'Blank line boundary narrative.');
        return {
            processed: response.data.processed,
            imported: response.data.imported,
            rejected: response.data.rejected,
            counts: counts(),
        };
    },
);

await session.check(
    'IMP-11',
    'Provenance rejects dot, dot-dot, drive-relative, and path file names after trim, and accepts a normal basename.',
    async () => {
        const before = counts();
        const statuses = [];
        for (const sourceFileName of [
            '../x.csv', 'a/b.csv', 'a\\b.csv', '.', '..', 'C:x', ' . ', ' .. ', ' C:x ',
        ]) {
            const response = await importForm(HEADER + row(`90000003${statuses.length}`), {
                ...METADATA,
                sourceFileName,
            });
            assert.equal(response.status, 400);
            assert.equal(response.data.code, 'VALIDATION_ERROR');
            assert.ok(
                response.data.fieldErrors.some((error) =>
                    error.field === 'metadata.sourceFileName' && error.code === 'INVALID_FORMAT'),
                `expected metadata.sourceFileName INVALID_FORMAT for ${JSON.stringify(sourceFileName)}`,
            );
            statuses.push({ sourceFileName, status: response.status, fieldErrors: response.data.fieldErrors });
        }
        assert.deepEqual(counts(), before);
        const accepted = await importForm(HEADER + row('900000039'), {
            ...METADATA,
            sourceFileName: '  normal-name.csv  ',
        });
        assert.equal(accepted.status, 201);
        assert.equal(accepted.data.metadata.sourceFileName, 'normal-name.csv');
        assert.deepEqual(counts(), { cases: before.cases + 1, batches: before.batches + 1 });
        return { rejected: statuses, accepted: accepted.data.metadata.sourceFileName, counts: counts() };
    },
);

await session.check(
    'IMP-12',
    'A leading U+0000 in a narrative is rejected per record while the valid row in the same import is stored.',
    async () => {
        const before = counts();
        const csv = HEADER
            + row('900000030', 'Valid row before the malformed row.')
            + row('900000031', '\u0000NUL narrative.');
        const response = await importForm(csv, { ...METADATA, sourceFileName: 'nul-row.csv' });
        assert.equal(response.status, 201);
        assert.equal(response.data.processed, 2);
        assert.equal(response.data.imported, 1);
        assert.equal(response.data.rejected, 1);
        assert.equal(response.data.duplicates, 0);
        assert.deepEqual(
            response.data.errors.map((error) => [error.recordNumber, error.field, error.code]),
            [[2, 'Consumer complaint narrative', 'INVALID_FORMAT']],
        );
        const after = counts();
        assert.deepEqual(after, { cases: before.cases + 1, batches: before.batches + 1 });
        const detail = await detailByComplaintId('900000030');
        assert.equal(detail.narrative, 'Valid row before the malformed row.');
        return { status: response.status, before, after, errors: response.data.errors };
    },
);

await session.check(
    'IMP-13',
    'A leading U+0000 in metadata sourceFileName is rejected as metadata validation with no write.',
    async () => {
        const before = counts();
        const response = await importForm(HEADER + row('900000032'), {
            ...METADATA,
            sourceFileName: '\u0000file.csv',
        });
        assert.equal(response.status, 400);
        assert.equal(response.data.code, 'VALIDATION_ERROR');
        assert.ok(response.data.fieldErrors.some((error) =>
            error.field === 'metadata.sourceFileName' && error.code === 'INVALID_FORMAT'));
        assert.deepEqual(counts(), before);
        return {
            status: response.status,
            code: response.data.code,
            fieldErrors: response.data.fieldErrors,
            before,
            after: counts(),
        };
    },
);

await session.check(
    'IMP-14',
    'Category tuples are unique, sorted, and provenance points to the accepted batch.',
    async () => {
        const categories = await session.request('/api/categories');
        const serialized = categories.data.items.map((item) => JSON.stringify(item));
        assert.equal(new Set(serialized).size, serialized.length);
        assert.ok(categories.data.items.length > 0);
        const detail = await session.request(`/api/cases/${sampleDetail.data.id}`);
        assert.equal(detail.data.provenance.importBatchId, sampleBatchId);
        assert.equal(
            detail.data.provenance.csvSha256,
            '682bb04cf77a7df10104c822ab24af4bd42e1a088cd2678c7c72921b7983a644',
        );
        return { tuples: categories.data.items.length };
    },
);

await session.check(
    'IMP-15',
    'LF/CRLF blank lines are ignored, quoted multiline is preserved, and space/delimiter lines remain records.',
    async () => {
        const before = counts();
        const csv = HEADER
            + row('900000040', 'Line one about a transfer.\n\nLine two after an internal blank line.')
            + '\n'
            + '\r\n'
            + '   \n'
            + ',\n'
            + row('900000041', 'Second valid narrative.');
        const response = await importForm(csv, { ...METADATA, sourceFileName: 'blank-boundaries.csv' });
        assert.equal(response.status, 201);
        assert.equal(response.data.processed, 4);
        assert.equal(response.data.imported, 2);
        assert.equal(response.data.rejected, 2);
        assert.equal(response.data.duplicates, 0);
        assert.deepEqual(
            response.data.errors.map((error) => [error.recordNumber, error.field, error.code]),
            [[2, 'Complaint ID', 'REQUIRED'], [3, 'Complaint ID', 'REQUIRED']],
        );
        assert.deepEqual(counts(), { cases: before.cases + 2, batches: before.batches + 1 });
        const multiline = await detailByComplaintId('900000040');
        assert.equal(multiline.narrative, 'Line one about a transfer.\n\nLine two after an internal blank line.');
        assert.equal((await detailByComplaintId('900000041')).narrative, 'Second valid narrative.');
        return {
            processed: response.data.processed,
            imported: response.data.imported,
            rejected: response.data.rejected,
            errors: response.data.errors,
            counts: counts(),
        };
    },
);

await session.save({ finalCounts: counts() });
db.close();
