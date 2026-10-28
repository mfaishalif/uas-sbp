// Seed an identical 512-case corpus (500 committed sample + 12 synthetic
// phrase fixtures) into a disposable backend. Used by search-phrases.mjs.
// Usage: node docs/testing/probes/seed-search-corpus.mjs <baseUrl>
import { readFile } from 'node:fs/promises';

const baseUrl = process.argv[2];
if (!baseUrl) throw new Error('usage: seed-search-corpus.mjs <baseUrl>');

const header = 'Complaint ID,Product,Sub-product,Issue,Sub-issue,'
    + 'Consumer complaint narrative,Company,Company response to consumer,Date received\n';
const fixtures = [
    ['940000001', 'The cash machine charged twice and the funds held were released.'],
    ['940000002', 'My duplicate charge and withheld balance were not fixed.'],
    ['940000003', 'A transfer was deducted but the recipient never received the funds.'],
    ['940000004', 'My transfer is missing from my checking account.'],
    ['940000005', 'Unauthorized wire transfer from my savings account.'],
    ['940000006', 'The ATM did not dispense cash but debited my account.'],
    ['940000007', 'A duplicate charge appeared after I used the cash machine.'],
    ['940000008', 'My balance is held after a duplicate charge at the ATM.'],
    ['940000009', 'The bank closed my checking account without warning and kept the remaining balance.'],
    ['940000010', 'An unexplained monthly fee was removed from my savings account.'],
    ['940000011', 'A scheduled payment from my savings account never arrived.'],
    ['940000012', 'I cannot access my checking account and the bank has not explained why.'],
];
const csv = header + fixtures.map(([id, narrative]) => [
    id,
    'Checking or savings account',
    'Checking account',
    'Managing an account',
    'Deposits and withdrawals',
    `"${narrative}"`,
    'Example Bank',
    'Closed with explanation',
    '2024-11-12',
].join(',') + '\n').join('');

async function importCsv(bytes, metadata, filename) {
    const form = new FormData();
    form.append('file', new Blob([bytes], { type: 'text/csv' }), filename);
    form.append('metadata', new Blob([JSON.stringify(metadata)], { type: 'application/json' }));
    const response = await fetch(`${baseUrl}/api/imports/cfpb`, { method: 'POST', body: form });
    const body = await response.json();
    if (response.status !== 201) {
        throw new Error(`seed import failed: ${response.status} ${JSON.stringify(body)}`);
    }
    return body;
}

const sample = await readFile(new URL('../../../data/cfpb/sample.csv', import.meta.url));
const sampleMetadata = {
    archiveUrl: 'https://files.consumerfinance.gov/f/documents/CCDB_Export_8_November_2024_through_December_2024.zip',
    sourceFileName: 'finresolve-sample.csv',
    periodStart: '2024-11-01',
    periodEnd: '2024-12-31',
};
const fixtureMetadata = {
    archiveUrl: 'https://example.com/search-fixture.zip',
    sourceFileName: 'search-fixture.csv',
    periodStart: '2024-11-01',
    periodEnd: '2024-12-31',
};
const sampleResult = await importCsv(sample, sampleMetadata, 'sample.csv');
const fixtureResult = await importCsv(Buffer.from(csv), fixtureMetadata, 'fixture.csv');
const list = await (await fetch(`${baseUrl}/api/cases`)).json();
console.log(JSON.stringify({
    baseUrl,
    sample: sampleResult.imported,
    fixture: fixtureResult.imported,
    total: list.totalElements,
}));
