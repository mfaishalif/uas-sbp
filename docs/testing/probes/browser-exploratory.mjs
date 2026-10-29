// Exploratory browser session for FinResolve.
// Usage: node browser-exploratory.mjs <baseUrl> <apiUrl> <evidenceDir> <output.json>
//
// The script runs against a disposable production deployment (Next.js + JAR).
// It writes screenshots and a structured JSON result for every scenario. It
// never mutates the repository database or application code.
import assert from 'node:assert/strict';
import { readFile, writeFile, mkdir } from 'node:fs/promises';
import path from 'node:path';
import { chromium } from 'playwright';
import AxeBuilder from '@axe-core/playwright';

const [baseUrl, apiUrl, evidenceDir, output] = process.argv.slice(2);
if (!baseUrl || !apiUrl || !evidenceDir || !output) {
  throw new Error('usage: browser-exploratory.mjs <baseUrl> <apiUrl> <evidenceDir> <output.json>');
}
await mkdir(evidenceDir, { recursive: true });

const samplePath = path.resolve(import.meta.dirname, '../../../data/cfpb/sample.csv');
const sampleBytes = await readFile(samplePath);
const archiveUrl =
  'https://files.consumerfinance.gov/f/documents/CCDB_Export_8_November_2024_through_December_2024.zip';

const results = [];
const pageErrors = [];
const consoleErrors = [];
const apiCalls = [];
let browser;
let page;

function snap(value, depth = 0) {
  if (value === null || value === undefined) return value;
  if (typeof value === 'string') {
    return value.length <= 1200 ? value : `${value.slice(0, 1200)}...[${value.length} chars]`;
  }
  if (typeof value !== 'object') return value;
  if (depth >= 4) return '[depth limit]';
  if (Array.isArray(value)) return value.slice(0, 12).map((item) => snap(item, depth + 1));
  const out = {};
  for (const [key, item] of Object.entries(value)) out[key] = snap(item, depth + 1);
  return out;
}

async function check(id, hypothesis, fn, options = {}) {
  const started = performance.now();
  const before = apiCalls.length;
  try {
    const detail = await fn();
    const result = detail?.result ?? 'PASS';
    results.push({
      id,
      hypothesis,
      result,
      durationMs: Math.round(performance.now() - started),
      detail: snap(detail),
      apiCalls: apiCalls.slice(before),
    });
    console.log(`${result} ${id}`);
  } catch (error) {
    if (options.screenshot !== false && page) {
      await page.screenshot({
        path: path.join(evidenceDir, `${id}-failure.png`),
        fullPage: true,
      }).catch(() => {});
    }
    results.push({
      id,
      hypothesis,
      result: 'FAIL',
      durationMs: Math.round(performance.now() - started),
      error: error.message,
      apiCalls: apiCalls.slice(before),
    });
    console.log(`FAIL ${id}: ${error.message}`);
  }
}

async function shot(name, target = page) {
  await target.screenshot({
    path: path.join(evidenceDir, `${name}.png`),
    fullPage: true,
  });
}

async function goto(target, route) {
  await target.goto(`${baseUrl}${route}`, { waitUntil: 'domcontentloaded' });
  await target.locator('main').waitFor({ state: 'visible', timeout: 30_000 });
}

async function api(route, init) {
  const response = await fetch(`${apiUrl}${route}`, init);
  const text = await response.text();
  let data;
  try { data = text ? JSON.parse(text) : null; } catch { data = text; }
  return { status: response.status, headers: response.headers, data };
}

async function fillMetadata(target, sourceFileName, overrides = {}) {
  await target.getByLabel('Archive URL', { exact: true }).fill(overrides.archiveUrl ?? archiveUrl);
  await target.getByLabel('Source file name', { exact: true }).fill(sourceFileName);
  await target.getByLabel('Period start', { exact: true }).fill(overrides.periodStart ?? '2024-11-01');
  await target.getByLabel('Period end', { exact: true }).fill(overrides.periodEnd ?? '2024-12-31');
}

async function selectSearchCategory(target) {
  await target.getByLabel('Product', { exact: true }).selectOption({ label: 'Checking or savings account' });
  await target.getByLabel('Issue', { exact: true }).selectOption({ label: 'Managing an account' });
  await target.locator('#search-sub-issue').selectOption({ label: 'Deposits and withdrawals' });
}

async function selectCaseCategory(target) {
  await target.getByLabel('Product', { exact: true }).selectOption({ label: 'Checking or savings account' });
  await target.getByLabel('Issue', { exact: true }).selectOption({ label: 'Managing an account' });
  await target.locator('#case-sub-issue').selectOption({ label: 'Deposits and withdrawals' });
}

async function runSearch(target, narrative) {
  await target.getByLabel('Complaint narrative', { exact: true }).fill(narrative);
  await target.getByRole('button', { name: 'Find precedents', exact: true }).click();
}

function parseScore(text) {
  const value = Number.parseFloat(String(text).replace(/[^0-9.]/g, ''));
  assert.ok(Number.isFinite(value), `unparseable score: ${text}`);
  return value;
}

async function axeCheck(target, label, ruleSet = 'full') {
  let builder = new AxeBuilder({ page: target });
  if (ruleSet !== 'full') builder = builder.withRules([ruleSet]);
  const analysis = await builder.analyze();
  return {
    violations: analysis.violations.map((violation) => ({
      id: violation.id,
      impact: violation.impact,
      help: violation.help,
      nodes: violation.nodes.map((node) => node.target.join(' ')),
    })),
    passes: analysis.passes.length,
    incomplete: analysis.incomplete.map((item) => ({
      id: item.id,
      impact: item.impact,
      nodes: item.nodes.map((node) => node.target.join(' ')),
    })),
  };
}

async function assertNoAxeViolations(target, label) {
  const analysis = await axeCheck(target, label);
  assert.deepEqual(
    analysis.violations,
    [],
    `${label} axe violations:\n${analysis.violations.map((v) => `${v.id} (${v.impact}): ${v.help} @ ${v.nodes.join(', ')}`).join('\n')}`,
  );
  return { rulesPassed: analysis.passes, incomplete: analysis.incomplete };
}

browser = await chromium.launch({ headless: true });
const context = await browser.newContext({
  viewport: { width: 1440, height: 1000 },
  locale: 'en-US',
});
page = await context.newPage();
page.on('pageerror', (error) => pageErrors.push(error.message));
page.on('console', (message) => {
  if (message.type() === 'error') consoleErrors.push(message.text());
});
page.on('response', (response) => {
  const url = response.url();
  if (!url.includes('/api/')) return;
  apiCalls.push({
    method: response.request().method(),
    path: new URL(url).pathname + new URL(url).search,
    status: response.status(),
  });
});
page.on('requestfailed', (request) => {
  const url = request.url();
  if (!url.includes('/api/')) return;
  apiCalls.push({
    method: request.method(),
    path: new URL(url).pathname + new URL(url).search,
    failed: request.failure()?.errorText ?? 'request failed',
  });
});

// ---------------------------------------------------------------------------
// Empty knowledge base and routing states
// ---------------------------------------------------------------------------
await goto(page, '/');

await check('B-01', 'Home renders the empty-catalog state with both columns on desktop.', async () => {
  await page.getByRole('heading', { name: 'Find context for your next complaint' }).waitFor();
  await page.getByText('No categories are available yet').waitFor();
  const columns = await page.getByTestId('search-layout').evaluate(
    (el) => getComputedStyle(el).gridTemplateColumns.split(' ').length,
  );
  assert.equal(columns, 2);
  await shot('desktop-home-empty');
  return { columns, catalogEmpty: true };
});

await check('B-02', 'Submitting the empty-catalog search form is blocked client-side and focuses Product.', async () => {
  const before = apiCalls.filter((call) => call.path === '/api/search').length;
  await page.getByRole('button', { name: 'Find precedents', exact: true }).click();
  await page.getByText('Choose a product.').waitFor();
  await page.getByText('Choose an issue.').waitFor();
  await page.getByText('Describe the complaint before searching.').waitFor();
  assert.equal(await page.evaluate(() => document.activeElement?.id), 'search-product');
  assert.equal(apiCalls.filter((call) => call.path === '/api/search').length, before);
  return { focus: 'search-product', requests: 0 };
});

await check('B-03', 'Case library shows its empty state with zero cases and no pagination.', async () => {
  await goto(page, '/cases');
  await page.getByRole('heading', { name: 'Case library' }).waitFor();
  await page.getByText('The case library is empty').waitFor();
  await page.getByText('0 cases · 20 per page').waitFor();
  assert.equal(await page.getByRole('navigation', { name: 'Case library pages' }).count(), 0);
  await shot('desktop-cases-empty');
  return { count: 0 };
});

await check('B-04', 'Add-case page on an empty catalog cannot save and reports required fields.', async () => {
  await goto(page, '/cases/new');
  await page.getByRole('heading', { name: 'Add analyst case' }).waitFor();
  await page.getByRole('button', { name: 'Save analyst case' }).click();
  await page.getByText('Choose a product.').waitFor();
  await page.getByText('Describe the complaint narrative.').waitFor();
  await page.getByText('Add notes about how the case was handled.').waitFor();
  assert.equal(await page.evaluate(() => document.activeElement?.id), 'case-product');
  const before = apiCalls.filter((call) => call.method === 'POST' && call.path === '/api/cases').length;
  assert.equal(before, 0);
  return { focus: 'case-product', requests: 0 };
});

await check('B-05', 'Import page starts with no report and a visible empty-report explanation.', async () => {
  await goto(page, '/imports');
  await page.getByRole('heading', { name: 'Import CFPB archive data' }).waitFor();
  await page.getByRole('heading', { name: 'The report appears here' }).waitFor();
  await shot('desktop-imports-empty');
  return { reportVisible: false };
});

await check('B-06', 'Invalid case id is mapped to a readable API error instead of crashing.', async () => {
  await goto(page, '/cases/not-a-uuid');
  await page.getByText('Request not understood').waitFor({ timeout: 20_000 });
  await page.getByRole('button', { name: 'Try again' }).waitFor();
  await shot('desktop-case-invalid-id');
  return { title: 'Request not understood' };
});

await check('B-07', 'Well-formed but unknown case UUID shows Case not found.', async () => {
  await goto(page, '/cases/00000000-0000-4000-8000-000000000000');
  await page.getByText('Case not found').waitFor({ timeout: 20_000 });
  await shot('desktop-case-not-found');
  return { title: 'Case not found' };
});

await check('B-08', 'Unknown frontend route renders the Next.js 404 page.', async () => {
  await goto(page, '/does-not-exist');
  await page.getByRole('heading', { name: 'Page not found' }).waitFor();
  await shot('desktop-route-not-found');
  return { heading: 'Page not found' };
});

// ---------------------------------------------------------------------------
// Import validation and reporting
// ---------------------------------------------------------------------------
await goto(page, '/imports');

await check('B-09', 'Submitting the import form without a file or metadata is blocked and focuses the file input.', async () => {
  await page.getByRole('button', { name: 'Import CSV' }).click();
  await page.getByText('Choose a CSV file to import.').waitFor();
  await page.getByText('Enter the archive URL.').waitFor();
  await page.getByText('Enter the period start.').waitFor();
  assert.equal(await page.evaluate(() => document.activeElement?.id), 'import-file');
  return { focus: 'import-file' };
}, { screenshot: false });

const badMetadataFile = path.join(evidenceDir, 'tiny.csv');
await writeFile(
  badMetadataFile,
  'Complaint ID,Product,Issue,Consumer complaint narrative,Date received\n995000999,Checking or savings account,Managing an account,"Tiny valid narrative.",2024-11-12\n',
);

await check('B-10', 'Invalid metadata (non-HTTPS URL, unsafe file name, reversed dates) is rejected before upload.', async () => {
  await page.getByLabel('CSV file', { exact: true }).setInputFiles(badMetadataFile);
  await page.getByLabel('Archive URL', { exact: true }).fill('http://example.com/archive.zip');
  await page.getByLabel('Source file name', { exact: true }).fill('../unsafe.csv');
  await page.getByLabel('Period start', { exact: true }).fill('2024-12-31');
  await page.getByLabel('Period end', { exact: true }).fill('2024-11-01');
  const before = apiCalls.filter((call) => call.path === '/api/imports/cfpb').length;
  await page.getByRole('button', { name: 'Import CSV' }).click();
  await page.getByText('Enter a valid HTTPS archive URL.').waitFor();
  await page.getByText('Use a file name without slashes, dot segments, or a drive prefix.').waitFor();
  await page.getByText('The period end cannot be before the period start.').waitFor();
  assert.equal(apiCalls.filter((call) => call.path === '/api/imports/cfpb').length, before);
  await shot('desktop-import-metadata-errors');
  return { requests: 0 };
});

await check('B-11', 'A file larger than 20 MiB is rejected client-side without a network request.', async () => {
  const oversized = path.join(evidenceDir, 'oversized.csv');
  const fd = await import('node:fs/promises');
  const handle = await fd.open(oversized, 'w');
  try {
    await handle.write(Buffer.alloc(20 * 1024 * 1024 + 1, 0x20));
  } finally {
    await handle.close();
  }
  await page.getByLabel('CSV file', { exact: true }).setInputFiles(oversized);
  await fillMetadata(page, 'oversized.csv');
  const before = apiCalls.filter((call) => call.path === '/api/imports/cfpb').length;
  await page.getByRole('button', { name: 'Import CSV' }).click();
  await page.getByText('The CSV must be 20 MiB (20,971,520 bytes) or smaller.').waitFor();
  assert.equal(apiCalls.filter((call) => call.path === '/api/imports/cfpb').length, before);
  return { bytes: 20 * 1024 * 1024 + 1, requests: 0 };
});

await check('B-12', 'A corrupt CSV reports INVALID_CSV and keeps the form and file selection intact.', async () => {
  const corrupt = path.join(evidenceDir, 'corrupt.csv');
  await writeFile(corrupt, 'NotAComplaintId,WrongHeader\n1,2\n');
  const casesBefore = (await api('/api/cases')).data.totalElements;
  await page.getByLabel('CSV file', { exact: true }).setInputFiles(corrupt);
  await fillMetadata(page, 'corrupt.csv');
  await page.getByRole('button', { name: 'Import CSV' }).click();
  await page.getByText('CSV file not accepted').waitFor({ timeout: 30_000 });
  await page.getByText('The file is not a valid CFPB CSV. Check the required header and try again.').waitFor();
  assert.equal(await page.getByLabel('Source file name', { exact: true }).inputValue(), 'corrupt.csv');
  assert.equal(await page.getByLabel('CSV file', { exact: true }).evaluate((el) => el.files.length), 1);
  assert.equal((await api('/api/cases')).data.totalElements, casesBefore);
  await shot('desktop-import-invalid-csv');
  return { casesBefore, casesAfter: casesBefore, title: 'CSV file not accepted' };
});

let firstBatchId;
await check('B-13', 'The committed 500-case sample imports through the UI and the catalog refreshes.', async () => {
  await page.getByLabel('CSV file', { exact: true }).setInputFiles(samplePath);
  await fillMetadata(page, 'finresolve-sample.csv');
  const casesBefore = (await api('/api/cases')).data.totalElements;
  await page.getByRole('button', { name: 'Import CSV' }).click();
  await page.getByRole('heading', { name: 'Import completed' }).waitFor({ timeout: 120_000 });
  const counts = page.locator('dl').filter({ hasText: 'Processed' }).first();
  const text = await counts.textContent();
  assert.match(text, /Processed\s*500/);
  assert.match(text, /Imported\s*500/);
  assert.match(text, /Duplicates\s*0/);
  assert.match(text, /Rejected\s*0/);
  const reportText = await page.locator('section').filter({ hasText: 'Import report' }).first().textContent();
  firstBatchId = reportText.match(/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/)?.[0];
  assert.ok(firstBatchId, 'batch id missing from report');
  assert.equal((await api('/api/cases')).data.totalElements, casesBefore + 500);
  const categories = (await api('/api/categories')).data.items.length;
  assert.ok(categories > 0);
  await shot('desktop-import-completed');
  return { batchId: firstBatchId, counts: 500, categories, casesBefore };
});

await check('B-14', 'Replaying the same bytes and metadata is shown as a replay of the same batch.', async () => {
  await page.getByRole('button', { name: 'Import another file' }).click();
  await page.getByLabel('CSV file', { exact: true }).setInputFiles(samplePath);
  await fillMetadata(page, 'finresolve-sample.csv');
  const casesBefore = (await api('/api/cases')).data.totalElements;
  await page.getByRole('button', { name: 'Import CSV' }).click();
  await page.getByRole('heading', { name: 'Replay detected' }).waitFor({ timeout: 120_000 });
  const reportText = await page.locator('section').filter({ hasText: 'Import report' }).first().textContent();
  const replayBatch = reportText.match(/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/)?.[0];
  assert.equal(replayBatch, firstBatchId);
  assert.equal((await api('/api/cases')).data.totalElements, casesBefore);
  await shot('desktop-import-replay');
  return { batchId: replayBatch, casesBefore, casesAfter: casesBefore };
});

await check('B-15', 'A CSV containing only existing CFPB ids reports duplicates without adding cases.', async () => {
  const header = 'Complaint ID,Product,Sub-product,Issue,Sub-issue,Consumer complaint narrative,Company,Company response to consumer,Date received\n';
  const escape = (value) => `"${String(value ?? '').replaceAll('"', '""')}"`;
  const existing = (await api('/api/cases?source=CFPB&size=3')).data.items;
  const rows = [];
  for (const item of existing) {
    const detail = (await api(`/api/cases/${item.id}`)).data;
    rows.push([
      detail.provenance.complaintId,
      escape(detail.product),
      escape(detail.subProduct),
      escape(detail.issue),
      escape(detail.subIssue),
      escape('Duplicate replay narrative.'),
      escape(detail.company),
      escape(detail.companyResponse),
      escape(detail.receivedDate ?? '2024-11-12'),
    ].join(','));
  }
  const subset = path.join(evidenceDir, 'duplicate-subset.csv');
  await writeFile(subset, `${header}${rows.join('\n')}\n`);
  await page.getByRole('button', { name: 'Import another file' }).click();
  await page.getByLabel('CSV file', { exact: true }).setInputFiles(subset);
  await fillMetadata(page, 'duplicate-subset.csv');
  const casesBefore = (await api('/api/cases')).data.totalElements;
  await page.getByRole('button', { name: 'Import CSV' }).click();
  await page.getByRole('heading', { name: 'Import completed' }).waitFor({ timeout: 60_000 });
  const text = await page.locator('dl').filter({ hasText: 'Processed' }).first().textContent();
  assert.match(text, /Processed\s*3/);
  assert.match(text, /Imported\s*0/);
  assert.match(text, /Duplicates\s*3/);
  assert.match(text, /Rejected\s*0/);
  assert.equal((await api('/api/cases')).data.totalElements, casesBefore);
  await shot('desktop-import-duplicates');
  return { processed: 3, imported: 0, duplicates: 3, cases: casesBefore };
});

let mixedCasesAfter;
await check('B-16', 'A mixed valid/invalid CSV stores the valid row, rejects the invalid row, and keeps counts consistent.', async () => {
  const header = 'Complaint ID,Product,Sub-product,Issue,Sub-issue,Consumer complaint narrative,Company,Company response to consumer,Date received\n';
  const mixed = path.join(evidenceDir, 'mixed-import.csv');
  await writeFile(mixed, header
    + '995000001,Checking or savings account,Checking account,Managing an account,Deposits and withdrawals,"A mixed import narrative about a missing transfer.",Example Bank,Closed with explanation,2024-11-12\n'
    + ',Checking or savings account,Checking account,Managing an account,Deposits and withdrawals,"A row without an id.",Example Bank,Closed with explanation,2024-11-12\n');
  await page.getByRole('button', { name: 'Import another file' }).click();
  await page.getByLabel('CSV file', { exact: true }).setInputFiles(mixed);
  await fillMetadata(page, 'mixed-import.csv');
  const casesBefore = (await api('/api/cases')).data.totalElements;
  await page.getByRole('button', { name: 'Import CSV' }).click();
  await page.getByRole('heading', { name: 'Import completed' }).waitFor({ timeout: 60_000 });
  const text = await page.locator('dl').filter({ hasText: 'Processed' }).first().textContent();
  assert.match(text, /Processed\s*2/);
  assert.match(text, /Imported\s*1/);
  assert.match(text, /Duplicates\s*0/);
  assert.match(text, /Rejected\s*1/);
  await page.getByText('1 record rejected').waitFor();
  await page.getByRole('table').getByText('REQUIRED', { exact: true }).waitFor();
  mixedCasesAfter = (await api('/api/cases')).data.totalElements;
  assert.equal(mixedCasesAfter, casesBefore + 1);
  await shot('desktop-import-mixed');
  return { processed: 2, imported: 1, rejected: 1, casesBefore, casesAfter: mixedCasesAfter };
});

// ---------------------------------------------------------------------------
// Search, explanations, references, navigation, and API recovery
// ---------------------------------------------------------------------------
await goto(page, '/');

await check('B-17', 'Category selects cascade: product enables issue, issue enables sub-issue, and changing product resets descendants.', async () => {
  await page.getByLabel('Product', { exact: true }).waitFor();
  await page.waitForFunction(() => {
    const select = document.querySelector('#search-product');
    return select && select.options.length > 1;
  });
  const issuesBefore = await page.getByLabel('Issue', { exact: true }).isDisabled();
  assert.equal(issuesBefore, true);
  await page.getByLabel('Product', { exact: true }).selectOption({ label: 'Checking or savings account' });
  assert.equal(await page.getByLabel('Issue', { exact: true }).isDisabled(), false);
  await page.getByLabel('Issue', { exact: true }).selectOption({ label: 'Managing an account' });
  assert.equal(await page.locator('#search-sub-issue').isDisabled(), false);
  const optionCount = await page.locator('#search-issue option').count();
  assert.ok(optionCount > 1);
  const products = await page.locator('#search-product option').count();
  assert.ok(products > 1);
  const subProducts = await page.locator('#search-sub-product option').allTextContents();
  assert.ok(subProducts.length > 1);
  await page.locator('#search-sub-product').selectOption({ index: 1 });
  const chosenSubProduct = await page.locator('#search-sub-product').inputValue();
  assert.ok(chosenSubProduct);
  await page.getByLabel('Product', { exact: true }).selectOption({ label: 'Money transfer, virtual currency, or money service' });
  assert.equal(await page.locator('#search-sub-product').inputValue(), '');
  assert.equal(await page.getByLabel('Issue', { exact: true }).inputValue(), '');
  assert.equal(await page.locator('#search-sub-issue').inputValue(), '');
  await shot('desktop-search-cascade');
  return { products, issues: optionCount, resetDescendants: true };
});

await check('B-18', 'Blank or whitespace-only narratives are rejected with focus on the narrative field.', async () => {
  await selectSearchCategory(page);
  await page.getByLabel('Complaint narrative', { exact: true }).fill('   ');
  await page.getByRole('button', { name: 'Find precedents', exact: true }).click();
  await page.getByText('Describe the complaint before searching.').waitFor();
  assert.equal(await page.evaluate(() => document.activeElement?.id), 'search-narrative');
  return { focus: 'search-narrative' };
}, { screenshot: false });

await check('B-19', 'Narrative without meaningful tokens maps NO_MEANINGFUL_TOKENS to actionable text.', async () => {
  await runSearch(page, 'the and or of');
  await page.getByText('Add meaningful words to the narrative.').waitFor({ timeout: 20_000 });
  await page.getByText('Check the form').waitFor();
  await shot('desktop-search-no-tokens');
  return { fieldCode: 'NO_MEANINGFUL_TOKENS' };
});

await check('B-20', 'An unrelated narrative returns NO_RELEVANT_CASES with an empty state and configuration.', async () => {
  await page.getByLabel('Complaint narrative', { exact: true }).fill('qzxvplm xwzqrpt');
  await page.getByRole('button', { name: 'Find precedents', exact: true }).click();
  await page.getByRole('heading', { name: 'No relevant precedents found' }).waitFor({ timeout: 30_000 });
  await page.getByRole('heading', { name: 'Actual server configuration' }).waitFor();
  await page.getByText('tfidf-cosine-v1').waitFor();
  assert.equal(await page.locator('article').count(), 0);
  await shot('desktop-search-no-results');
  return { status: 'NO_RELEVANT_CASES', corpus: '500+' };
});

await check('B-21', 'A known complaint returns ranked matches with scores, four category reasons, shared terms, and warnings.', async () => {
  await runSearch(page, 'Money left my bank balance twice even though I only authorized one payment.');
  await page.getByRole('heading', { name: 'Precedent matches' }).waitFor({ timeout: 30_000 });
  const cards = page.locator('article').filter({ hasText: 'Similarity score' });
  const count = await cards.count();
  assert.ok(count >= 1 && count <= 5, `unexpected match count ${count}`);
  const scores = [];
  for (let i = 0; i < count; i += 1) {
    const card = cards.nth(i);
    await card.getByText('Category reasons', { exact: true }).waitFor();
    const reasons = await card.getByText('Category reasons', { exact: true }).locator('..').locator('li').count();
    assert.equal(reasons, 4);
    await card.getByText('Shared terms', { exact: true }).waitFor();
    const scoreText = await card.locator('p').filter({ hasText: /^\d{1,3}\.\d%$/ }).first().textContent();
    scores.push(parseScore(scoreText));
    const category = parseScore(await card.getByText('Category score').locator('..').locator('span').first().textContent());
    const textScore = parseScore(await card.getByText('Text score').locator('..').locator('span').first().textContent());
    assert.ok(category >= 0 && category <= 100);
    assert.ok(textScore > 0 && textScore <= 100);
  }
  assert.deepEqual(scores, [...scores].sort((a, b) => b - a));
  const bodyText = await page.locator('body').textContent();
  assert.match(bodyText, /not a\s+probability/i);
  await page.getByText('Server warning').first().waitFor();
  await page.getByText(/tfidf-cosine-v1/).first().waitFor();
  await shot('desktop-search-results');
  return { matches: count, scores };
});

await check('B-22', 'Selecting a precedent toggles the reference control and updates the selection bar.', async () => {
  const firstCard = page.locator('article').filter({ hasText: 'Similarity score' }).first();
  await firstCard.getByRole('button', { name: 'Use as reference' }).click();
  const remove = firstCard.getByRole('button', { name: 'Remove reference' });
  await remove.waitFor();
  assert.equal(await remove.getAttribute('aria-pressed'), 'true');
  await page.getByText('1 selected as a reference.').waitFor();
  const second = page.locator('article').filter({ hasText: 'Similarity score' }).nth(1);
  if (await second.count()) {
    await second.getByRole('button', { name: 'Use as reference' }).click();
    await page.getByText('2 selected as references.').waitFor();
  }
  await shot('desktop-search-selected-reference');
  return { selected: await second.count() ? 2 : 1 };
});

await check('B-23', 'Client navigation keeps search results and selection, while a full refresh clears them (documented behavior).', async () => {
  await page.getByRole('navigation', { name: 'Main navigation' })
    .getByRole('link', { name: 'Case library' }).click();
  await page.getByRole('heading', { name: 'Case library' }).waitFor();
  await page.goBack();
  await page.getByRole('heading', { name: 'Precedent matches' }).waitFor({ timeout: 30_000 });
  await page.getByRole('button', { name: 'Remove reference' }).first().waitFor();
  await page.goForward();
  await page.getByRole('heading', { name: 'Case library' }).waitFor({ timeout: 30_000 });
  await page.goBack();
  await page.getByRole('heading', { name: 'Precedent matches' }).waitFor({ timeout: 30_000 });
  await page.getByRole('button', { name: 'Remove reference' }).first().waitFor();
  await page.reload({ waitUntil: 'domcontentloaded' });
  await page.getByRole('heading', { name: 'Find context for your next complaint' }).waitFor();
  assert.equal(await page.getByLabel('Complaint narrative', { exact: true }).inputValue(), '');
  assert.equal(await page.getByText('Similarity score').count(), 0);
  return { spaPreserved: true, forwardWorks: true, refreshCleared: true };
});

await check('B-24', 'A rapid double submit of the search form produces exactly one POST /api/search.', async () => {
  await selectSearchCategory(page);
  await page.getByLabel('Complaint narrative', { exact: true }).fill('My transfer is missing from my checking account.');
  const before = apiCalls.filter((call) => call.method === 'POST' && call.path === '/api/search').length;
  await page.locator('form').filter({ has: page.getByRole('button', { name: 'Find precedents', exact: true }) })
    .evaluate((form) => { form.requestSubmit(); form.requestSubmit(); });
  await page.getByRole('heading', { name: 'Precedent matches' }).waitFor({ timeout: 30_000 });
  const after = apiCalls.filter((call) => call.method === 'POST' && call.path === '/api/search').length;
  assert.equal(after - before, 1);
  return { requests: after - before };
});

await check('B-25', 'A transport failure shows a recoverable error, keeps inputs, and retry succeeds.', async () => {
  let fail = true;
  await page.route('**/api/search', async (route) => {
    if (fail) await route.abort('failed');
    else await route.continue();
  });
  try {
    await runSearch(page, 'Money left my bank balance twice even though I only authorized one payment.');
    await page.getByText('Cannot reach the server').waitFor({ timeout: 20_000 });
    await page.getByText('No readable response was received. Check that the backend is running and try again.').waitFor();
    assert.equal(
      await page.getByLabel('Complaint narrative', { exact: true }).inputValue(),
      'Money left my bank balance twice even though I only authorized one payment.',
    );
    fail = false;
    await page.getByRole('button', { name: 'Find precedents', exact: true }).click();
    await page.getByRole('heading', { name: 'Precedent matches' }).waitFor({ timeout: 30_000 });
    await shot('desktop-search-recovered');
    return { recovered: true };
  } finally {
    await page.unroute('**/api/search');
  }
});

await check('B-26', 'Desktop results pass a full axe accessibility scan.', async () => {
  return await assertNoAxeViolations(page, 'search-results');
});

// ---------------------------------------------------------------------------
// Retention, detail, edit, references, library
// ---------------------------------------------------------------------------
let analystId;
let analystCreatedAt;
let selectedReferenceCount;

await check('B-27', 'Continue to retention carries category, narrative, and selected references without URL state.', async () => {
  const cards = page.locator('article').filter({ hasText: 'Similarity score' });
  await cards.first().getByRole('button', { name: 'Use as reference' }).click();
  if (await cards.nth(1).count()) {
    await cards.nth(1).getByRole('button', { name: 'Use as reference' }).click();
  }
  selectedReferenceCount = await page.getByRole('button', { name: 'Remove reference' }).count();
  assert.ok(selectedReferenceCount >= 1);
  await page.getByRole('button', { name: 'Continue to retention' }).click();
  await page.waitForURL(/\/cases\/new$/);
  assert.equal(new URL(page.url()).search, '');
  assert.equal(
    await page.getByLabel('Complaint narrative', { exact: true }).inputValue(),
    'Money left my bank balance twice even though I only authorized one payment.',
  );
  await page.getByText(`${selectedReferenceCount} / 20`).waitFor();
  await shot('desktop-retention-prefilled');
  return { references: selectedReferenceCount };
});

await check('B-28', 'Saving a retained case with a synthetic double submit creates exactly one case and navigates to it.', async () => {
  await page.getByLabel('Handling notes', { exact: true }).fill(
    'Reviewed the duplicate authorization and requested a transfer trace.',
  );
  await page.locator('#case-company').fill('QA Example Bank');
  await page.locator('#case-evaluation-notes').fill('Initial browser evaluation.');
  const before = (await api('/api/cases')).data.totalElements;
  const beforePosts = apiCalls.filter((call) => call.method === 'POST' && call.path === '/api/cases').length;
  await page.locator('form').filter({ has: page.getByRole('button', { name: 'Save analyst case' }) })
    .evaluate((form) => { form.requestSubmit(); form.requestSubmit(); });
  await page.waitForURL(/\/cases\/[0-9a-f-]{36}$/, { timeout: 30_000 });
  analystId = new URL(page.url()).pathname.split('/').pop();
  const after = (await api('/api/cases')).data.totalElements;
  assert.equal(after, before + 1);
  const posts = apiCalls.filter((call) => call.method === 'POST' && call.path === '/api/cases').length;
  assert.equal(posts - beforePosts, 1);
  const detail = (await api(`/api/cases/${analystId}`)).data;
  analystCreatedAt = detail.createdAt;
  assert.equal(detail.referenceCaseIds.length, selectedReferenceCount);
  await page.getByRole('heading', { name: 'Complaint narrative' }).waitFor();
  await shot('desktop-case-detail-analyst');
  return { analystId, casesBefore: before, casesAfter: after, posts: posts - beforePosts };
});

await check('B-29', 'Analyst detail shows notes, outcome, references, provenance, and an editable link.', async () => {
  await page.getByText('Reviewed the duplicate authorization and requested a transfer trace.').waitFor();
  await page.getByText('Initial browser evaluation.').waitFor();
  await page.getByRole('heading', { name: 'References' }).waitFor();
  await page.getByText('QA Example Bank').waitFor();
  await page.getByText('Retained directly by an analyst. No CFPB archive provenance.').waitFor();
  assert.ok(await page.getByRole('link', { name: 'Edit case' }).count());
  const referenceLinks = await page.getByRole('heading', { name: 'References' })
    .locator('..').locator('a[href^="/cases/"]').count();
  assert.equal(referenceLinks, selectedReferenceCount);
  return { references: referenceLinks };
});

await check('B-30', 'Edit form preloads the complete case; clearing optional fields and removing references persists.', async () => {
  await page.getByRole('link', { name: 'Edit case' }).click();
  await page.getByRole('heading', { name: 'Edit analyst case' }).waitFor();
  assert.equal(
    await page.getByLabel('Handling notes', { exact: true }).inputValue(),
    'Reviewed the duplicate authorization and requested a transfer trace.',
  );
  await page.locator('#case-company').fill('');
  await page.locator('#case-company-response').fill('');
  await page.locator('#case-evaluation-notes').fill('Edited evaluation after QA review.');
  await page.getByLabel('Outcome assessment', { exact: true }).selectOption('RESOLVED');
  const removeButtons = page.getByRole('button', { name: /^Remove reference / });
  const referencesBefore = await removeButtons.count();
  if (referencesBefore > 0) await removeButtons.first().click();
  await page.getByRole('button', { name: 'Save changes' }).click();
  await page.waitForURL(new RegExp(`/cases/${analystId}$`), { timeout: 30_000 });
  await page.getByText('Edited evaluation after QA review.').waitFor();
  await page.getByText('Resolved', { exact: true }).waitFor();
  await page.getByText('Not recorded').first().waitFor();
  const detail = (await api(`/api/cases/${analystId}`)).data;
  assert.equal(detail.analystNotes.evaluationNotes, 'Edited evaluation after QA review.');
  assert.equal(detail.analystNotes.outcomeAssessment, 'RESOLVED');
  assert.equal(detail.company, null);
  assert.equal(detail.companyResponse, null);
  assert.equal(detail.createdAt, analystCreatedAt);
  assert.equal(detail.referenceCaseIds.length, referencesBefore - 1);
  await shot('desktop-case-detail-edited');
  return {
    createdAtPreserved: true,
    referencesBefore,
    referencesAfter: detail.referenceCaseIds.length,
    company: detail.company,
    companyResponse: detail.companyResponse,
  };
});

await check('B-31', 'Reference picker opens as a dialog, filters/paginates, adds a reference, and closes with Escape.', async () => {
  await page.getByRole('link', { name: 'Edit case' }).click();
  await page.getByRole('heading', { name: 'Edit analyst case' }).waitFor();
  const before = await page.getByRole('button', { name: /^Remove reference / }).count();
  await page.getByRole('button', { name: 'Add reference from case library' }).click();
  const dialog = page.getByRole('dialog', { name: 'Add reference from the case library' });
  await dialog.waitFor();
  await dialog.getByLabel('Source', { exact: true }).selectOption('CFPB');
  await dialog.getByText(/\d+ available/).waitFor();
  const addButtons = dialog.getByRole('button', { name: 'Add reference' });
  await addButtons.first().waitFor();
  await addButtons.first().click();
  await page.getByRole('button', { name: 'Close' }).click();
  await page.getByText(`${before + 1} / 20`).waitFor();
  await page.getByRole('button', { name: 'Add reference from case library' }).click();
  await dialog.waitFor();
  await page.keyboard.press('Escape');
  await dialog.waitFor({ state: 'hidden' });
  await page.getByRole('button', { name: 'Save changes' }).click();
  await page.waitForURL(new RegExp(`/cases/${analystId}$`), { timeout: 30_000 });
  const detail = (await api(`/api/cases/${analystId}`)).data;
  assert.equal(detail.referenceCaseIds.length, before + 1);
  await shot('desktop-case-reference-picker');
  return { referencesBefore: before, referencesAfter: detail.referenceCaseIds.length };
});

await check('B-32', 'Case library filters CFPB and ANALYST, paginates 20 per page, and keeps counts consistent.', async () => {
  await goto(page, '/cases');
  await page.getByRole('heading', { name: 'Case library' }).waitFor();
  await page.getByRole('status').filter({ hasText: /cases · 20 per page/ }).waitFor();
  const allText = await page.getByRole('status').filter({ hasText: /cases/ }).textContent();
  assert.match(allText, /50[0-9] cases · 20 per page/);
  assert.equal(await page.locator('article').count(), 20);
  const pager = page.getByRole('navigation', { name: 'Case library pages' });
  await pager.getByRole('button', { name: 'Next' }).click();
  await page.getByText('Page 2 of', { exact: false }).waitFor();
  await pager.getByRole('button', { name: 'Previous' }).click();
  await page.getByText('Page 1 of', { exact: false }).waitFor();
  const expectedCfpb = (await api('/api/cases?source=CFPB')).data.totalElements;
  await page.getByLabel('Source', { exact: true }).selectOption('CFPB');
  await page.getByText(`${expectedCfpb} cases in this source · 20 per page`).waitFor();
  const expectedAnalyst = (await api('/api/cases?source=ANALYST')).data.totalElements;
  await page.getByLabel('Source', { exact: true }).selectOption('ANALYST');
  await page.getByText(`${expectedAnalyst} case${expectedAnalyst === 1 ? '' : 's'} in this source · 20 per page`).waitFor();
  assert.equal(await page.locator('article').count(), expectedAnalyst);
  await shot('desktop-cases-analyst-filter');
  return { all: allText?.trim(), cfpb: expectedCfpb, analyst: expectedAnalyst };
});

await check('B-33', 'A CFPB archive case is read-only in the UI and its edit route refuses editing.', async () => {
  const cfpb = (await api('/api/cases?source=CFPB&size=1')).data.items[0];
  await goto(page, `/cases/${cfpb.id}`);
  await page.getByRole('heading', { name: 'Complaint narrative' }).waitFor();
  await page.getByText('Read-only archive case').waitFor();
  await page.getByText('CFPB provenance').waitFor();
  assert.equal(await page.getByRole('link', { name: 'Edit case' }).count(), 0);
  await shot('desktop-case-detail-cfpb');
  await goto(page, `/cases/${cfpb.id}/edit`);
  await page.getByRole('heading', { name: 'This CFPB case cannot be edited' }).waitFor();
  assert.equal(await page.getByLabel('Handling notes', { exact: true }).count(), 0);
  await shot('desktop-case-edit-cfpb-blocked');
  return { cfpbId: cfpb.id };
});

await check('B-34', 'A simulated 500 on case detail shows an error and recovers through Try again.', async () => {
  const cfpb = (await api('/api/cases?source=CFPB&size=1')).data.items[0];
  let fail = true;
  await page.route(`**/api/cases/${cfpb.id}`, async (route) => {
    if (fail) {
      await route.fulfill({
        status: 500,
        contentType: 'application/json',
        body: JSON.stringify({
          code: 'INTERNAL_ERROR',
          message: 'Injected failure',
          fieldErrors: [],
          timestamp: new Date().toISOString(),
        }),
      });
    } else await route.continue();
  });
  try {
    await goto(page, `/cases/${cfpb.id}`);
    await page.getByText('Something went wrong').waitFor({ timeout: 20_000 });
    fail = false;
    await page.getByRole('button', { name: 'Try again' }).click();
    await page.getByRole('heading', { name: 'Complaint narrative' }).waitFor({ timeout: 20_000 });
    return { recovered: true };
  } finally {
    await page.unroute(`**/api/cases/${cfpb.id}`);
  }
});

await check('B-35', 'A failed analyst-case save keeps every field, then retry succeeds exactly once.', async () => {
  await goto(page, '/cases/new');
  await page.getByRole('heading', { name: 'Add analyst case' }).waitFor();
  await selectCaseCategory(page);
  await page.getByLabel('Complaint narrative', { exact: true }).fill('A save-retry analyst narrative about a duplicate charge.');
  await page.getByLabel('Handling notes', { exact: true }).fill('Retry save notes must survive a transport failure.');
  await page.locator('#case-evaluation-notes').fill('Retry evaluation.');
  let fail = true;
  await page.route('**/api/cases', async (route) => {
    if (route.request().method() === 'POST' && fail) await route.abort('failed');
    else await route.continue();
  });
  try {
    await page.getByRole('button', { name: 'Save analyst case' }).click();
    await page.getByText('Cannot reach the server').waitFor({ timeout: 20_000 });
    assert.equal(
      await page.getByLabel('Handling notes', { exact: true }).inputValue(),
      'Retry save notes must survive a transport failure.',
    );
    fail = false;
    const before = (await api('/api/cases')).data.totalElements;
    await page.getByRole('button', { name: 'Save analyst case' }).click();
    await page.waitForURL(/\/cases\/[0-9a-f-]{36}$/, { timeout: 30_000 });
    const after = (await api('/api/cases')).data.totalElements;
    assert.equal(after, before + 1);
    return { fieldsRetained: true, casesBefore: before, casesAfter: after };
  } finally {
    await page.unroute('**/api/cases');
  }
});

await check('B-36', 'Full axe scans pass on case library, analyst detail, edit, import, and empty-catalog pages.', async () => {
  const pages = [];
  const targets = [
    ['/cases', 'case-library'],
    [`/cases/${analystId}`, 'case-detail-analyst'],
    [`/cases/${analystId}/edit`, 'case-edit-analyst'],
    ['/imports', 'imports'],
    ['/cases/new', 'case-new'],
  ];
  const headings = {
    'case-library': 'Case library',
    'case-detail-analyst': 'Complaint narrative',
    'case-edit-analyst': 'Edit analyst case',
    imports: 'Import CFPB archive data',
    'case-new': 'Add analyst case',
  };
  for (const [route, label] of targets) {
    await goto(page, route);
    await page.getByRole('heading', { name: headings[label] }).first().waitFor({ timeout: 30_000 });
    const analysis = await assertNoAxeViolations(page, label);
    pages.push({ label, rulesPassed: analysis.rulesPassed });
  }
  return { pages };
});

// ---------------------------------------------------------------------------
// Keyboard and mobile
// ---------------------------------------------------------------------------
await goto(page, '/');

await check('B-37', 'Keyboard skip link is first, becomes visible on focus, and jumps to main content.', async () => {
  await page.keyboard.press('Tab');
  const skip = page.locator('.skip-link');
  assert.equal(await skip.evaluate((el) => el === document.activeElement), true);
  const box = await skip.boundingBox();
  assert.ok(box && box.y >= 0);
  await page.keyboard.press('Enter');
  await page.waitForURL(/#main-content$/);
  return { skipLinkFocus: true };
});

await check('B-38', 'Keyboard traversal reaches navigation and form controls with a visible focus indicator.', async () => {
  await page.keyboard.press('Tab'); // skip link
  await page.keyboard.press('Tab'); // brand or first nav link
  const active = await page.evaluate(() => {
    const el = document.activeElement;
    return {
      tag: el?.tagName,
      text: (el?.textContent ?? '').trim().slice(0, 60),
      outline: el ? getComputedStyle(el).outlineStyle : null,
      boxShadow: el ? getComputedStyle(el).boxShadow : null,
    };
  });
  assert.notEqual(active.tag, 'BODY');
  assert.ok(active.outline !== 'none' || active.boxShadow !== 'none', JSON.stringify(active));
  return active;
});

await check('B-39', 'Mobile 390x844: empty catalog, search results, library, and imports have no horizontal overflow and 48px targets.', async () => {
  await page.setViewportSize({ width: 390, height: 844 });
  await goto(page, '/');
  await page.getByRole('heading', { name: 'Find context for your next complaint' }).waitFor();
  const columns = await page.getByTestId('search-layout').evaluate(
    (el) => getComputedStyle(el).gridTemplateColumns.split(' ').length,
  );
  assert.equal(columns, 1);
  const overflow = await page.evaluate(
    () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
  );
  assert.ok(overflow <= 1, `home overflow ${overflow}`);
  await page.getByRole('navigation', { name: 'Main navigation' })
    .getByRole('link', { name: 'Case library' }).waitFor();
  await shot('mobile-home');

  const targets = [];
  for (const label of ['Find precedents']) {
    const button = page.getByRole('button', { name: label, exact: true });
    const box = await button.boundingBox();
    targets.push({ label, height: box?.height });
    assert.ok((box?.height ?? 0) >= 48, `${label} height ${box?.height}`);
  }
  const selectBox = await page.getByLabel('Product', { exact: true }).boundingBox();
  targets.push({ label: 'Product select', height: selectBox?.height });
  assert.ok((selectBox?.height ?? 0) >= 48, `select height ${selectBox?.height}`);

  await goto(page, '/cases');
  await page.getByRole('heading', { name: 'Case library' }).waitFor();
  const casesOverflow = await page.evaluate(
    () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
  );
  assert.ok(casesOverflow <= 1, `cases overflow ${casesOverflow}`);
  await shot('mobile-cases');

  await goto(page, '/imports');
  await page.getByRole('heading', { name: 'Import CFPB archive data' }).waitFor();
  const importsOverflow = await page.evaluate(
    () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
  );
  assert.ok(importsOverflow <= 1, `imports overflow ${importsOverflow}`);
  await shot('mobile-imports');

  await goto(page, '/');
  await selectSearchCategory(page);
  await runSearch(page, 'My transfer is missing from my checking account.');
  await page.getByRole('heading', { name: 'Precedent matches' }).waitFor({ timeout: 30_000 });
  const resultColumns = await page.getByTestId('search-layout').evaluate(
    (el) => getComputedStyle(el).gridTemplateColumns.split(' ').length,
  );
  assert.equal(resultColumns, 1);
  const resultsOverflow = await page.evaluate(
    () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
  );
  assert.ok(resultsOverflow <= 1, `results overflow ${resultsOverflow}`);
  await shot('mobile-search-results');

  await goto(page, `/cases/${analystId}`);
  await page.getByRole('heading', { name: 'Complaint narrative' }).waitFor();
  const detailOverflow = await page.evaluate(
    () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
  );
  assert.ok(detailOverflow <= 1, `detail overflow ${detailOverflow}`);
  await shot('mobile-case-detail');

  await goto(page, `/cases/${analystId}/edit`);
  await page.getByRole('heading', { name: 'Edit analyst case' }).waitFor();
  const editOverflow = await page.evaluate(
    () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
  );
  assert.ok(editOverflow <= 1, `edit overflow ${editOverflow}`);
  await shot('mobile-case-edit');

  await goto(page, '/cases/new');
  await page.getByRole('heading', { name: 'Add analyst case' }).waitFor();
  const newOverflow = await page.evaluate(
    () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
  );
  assert.ok(newOverflow <= 1, `new overflow ${newOverflow}`);
  await shot('mobile-case-new');

  await page.setViewportSize({ width: 1440, height: 1000 });
  return {
    columns, overflow, casesOverflow, importsOverflow, resultColumns, resultsOverflow,
    detailOverflow, editOverflow, newOverflow, targets,
  };
});

await check('B-40', 'Mobile pages pass a full axe scan.', async () => {
  await page.setViewportSize({ width: 390, height: 844 });
  await goto(page, '/');
  await page.getByRole('heading', { name: 'Find context for your next complaint' }).waitFor();
  const analysis = await assertNoAxeViolations(page, 'mobile-home');
  await page.setViewportSize({ width: 1440, height: 1000 });
  return analysis;
});

await check('B-41', 'No uncaught page errors occurred during the session.', async () => {
  assert.deepEqual(pageErrors, []);
  return { pageErrors: [] };
});

await check('B-42', 'Console errors are limited to deliberately simulated network/API failures.', async () => {
  const unexpected = consoleErrors.filter((message) =>
    !/Failed to fetch|Failed to load resource|net::ERR_FAILED|500 \(Internal Server Error\)|ERR_ABORTED/i.test(message));
  assert.deepEqual(unexpected, []);
  return { consoleErrorCount: consoleErrors.length, unexpected };
});

let restartSnapshot = null;
if (analystId) {
  const countsBeforeRestart = {
    total: (await api('/api/cases')).data.totalElements,
    cfpb: (await api('/api/cases?source=CFPB')).data.totalElements,
    analyst: (await api('/api/cases?source=ANALYST')).data.totalElements,
  };
  const analystDetail = (await api(`/api/cases/${analystId}`)).data;
  restartSnapshot = {
    analystId,
    analystCreatedAt,
    total: countsBeforeRestart.total,
    cfpb: countsBeforeRestart.cfpb,
    analyst: countsBeforeRestart.analyst,
    narrative: analystDetail.narrative,
    handlingNotes: analystDetail.analystNotes.handlingNotes,
    evaluationNotes: analystDetail.analystNotes.evaluationNotes,
    outcomeAssessment: analystDetail.analystNotes.outcomeAssessment,
    referenceIds: analystDetail.referenceCaseIds,
    cfpbId: (await api('/api/cases?source=CFPB&size=1')).data.items[0].id,
  };
  await writeFile(
    path.join(evidenceDir, 'browser-restart-state.json'),
    `${JSON.stringify(restartSnapshot, null, 2)}\n`,
  );
  await shot('desktop-final-search-results');
  await page.setViewportSize({ width: 390, height: 844 });
  await goto(page, `/cases/${analystId}`);
  await page.getByRole('heading', { name: 'Complaint narrative' }).waitFor();
  await shot('mobile-case-detail-analyst');
}

const summary = {
  scenarios: results.length,
  passed: results.filter((item) => item.result === 'PASS').length,
  failed: results.filter((item) => item.result === 'FAIL').length,
  blocked: results.filter((item) => item.result === 'BLOCKED').length,
  observations: results.filter((item) => item.result === 'OBSERVATION').length,
  pageErrors: pageErrors.length,
  consoleErrors: consoleErrors.length,
  apiCalls: apiCalls.length,
};
await writeFile(output, `${JSON.stringify({
  session: 'browser-exploratory',
  baseUrl,
  apiUrl,
  startedAt: new Date().toISOString(),
  summary,
  results,
  pageErrors,
  consoleErrors,
  apiCalls,
}, null, 2)}\n`);
console.log(JSON.stringify({ output, ...summary }));
await browser.close();
if (summary.failed > 0) process.exitCode = 1;
