import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import test from 'node:test';

import { evaluationRows, parseCsvRecords } from './evaluation-fixtures.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const repoRoot = path.resolve(here, '..');
const libUrl = pathToFileURL(path.join(repoRoot, 'docs/testing/probes/lib.mjs')).href;

test('evaluation fixture maps columns by header and keeps Q23 negative', async () => {
    const rows = evaluationRows(await readFile(
        path.join(repoRoot, 'docs/evaluation/queries.csv'), 'utf8'));
    assert.equal(rows.length, 24);
    const q23 = rows.find((row) => row.queryId === 'Q23');
    assert.equal(q23.kind, 'negative');
    assert.equal(q23.product, 'Checking or savings account');
    assert.equal(q23.issue, 'Unexpected or other fees');
    assert.match(q23.narrative, /^The coffee shop added an extra charge/);
    assert.equal(q23.scenario, 'Retail fee question sharing charge and fee words');
});

test('evaluation fixture rejects missing, ambiguous, and malformed structure', () => {
    assert.throws(
        () => evaluationRows('queryId,kind,product,subProduct,issue,subIssue,narrative\n'
            + 'Q01,complaint,p,sp,i,si,n\n'),
        /missing required column: scenario/);
    assert.throws(
        () => evaluationRows('queryId,kind,kind,product,subProduct,issue,subIssue,narrative,scenario\n'
            + 'Q01,complaint,negative,p,sp,i,si,n,s\n'),
        /ambiguous duplicate column: kind/);
    assert.throws(
        () => evaluationRows('queryId,kind,product,subProduct,issue,subIssue,narrative,scenario\n'
            + 'Q01,complaint,p,sp,i,si,n\n'),
        /has 7 fields; header has 8/);
    assert.throws(
        () => parseCsvRecords('queryId,narrative\nQ01,"unterminated\n'),
        /unterminated quoted field/);
});

test('CSV parser keeps quoted newlines and empty records', () => {
    const records = parseCsvRecords('a,b\n1,"line one\nline two"\n\n2,x\n');
    assert.deepEqual(records, [
        ['a', 'b'],
        ['1', 'line one\nline two'],
        [''],
        ['2', 'x'],
    ]);
});

function runFixture(mode, root) {
    const output = path.join(root, `${mode}.json`);
    const script = path.join(root, `fixture-${mode}.mjs`);
    return writeFile(script, `
        const { createSession } = await import(process.argv[2]);
        const session = createSession({ name: 'fixture', baseUrl: 'http://127.0.0.1:1', output: process.argv[3] });
        if (process.argv[4] === 'fail' || process.argv[4] === 'both') {
            await session.check('FIX-FAIL', 'forced failure', async () => { throw new Error('forced'); });
        }
        if (process.argv[4] === 'blocked' || process.argv[4] === 'both') {
            await session.blocked('FIX-BLOCKED', 'forced blocked', { reason: 'fixture' });
        }
        await session.check('FIX-PASS', 'forced pass', async () => ({ result: 'PASS', detail: 'ok' }));
        await session.save();
    `).then(() => spawnSync(
        process.execPath,
        [script, libUrl, output, mode],
        { encoding: 'utf8' },
    )).then((result) => ({ result, output }));
}

test('probe helper subprocess exits 0 for PASS, 1 for FAIL, and 2 for BLOCKED', async () => {
    const root = await mkdtemp(path.join(tmpdir(), 'finresolve-probe-exit-'));
    try {
        const pass = await runFixture('pass', root);
        assert.equal(pass.result.status, 0, pass.result.stderr);
        assert.equal(JSON.parse(await readFile(pass.output, 'utf8')).exitCode, 0);

        const fail = await runFixture('fail', root);
        assert.equal(fail.result.status, 1, fail.result.stderr);
        assert.equal(JSON.parse(await readFile(fail.output, 'utf8')).exitCode, 1);

        const blocked = await runFixture('blocked', root);
        assert.equal(blocked.result.status, 2, blocked.result.stderr);
        assert.equal(JSON.parse(await readFile(blocked.output, 'utf8')).exitCode, 2);

        const both = await runFixture('both', root);
        assert.equal(both.result.status, 1, both.result.stderr);
        assert.equal(JSON.parse(await readFile(both.output, 'utf8')).exitCode, 1);
    } finally {
        await rm(root, { recursive: true, force: true });
    }
});
