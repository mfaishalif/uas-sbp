// Post-restart verification through the public UI and API.
// Usage: node browser-restart-verify.mjs <baseUrl> <apiUrl> <state.json> <evidenceDir> <output.json>
import assert from 'node:assert/strict';
import { readFile, writeFile, mkdir } from 'node:fs/promises';
import path from 'node:path';
import { chromium } from 'playwright';

const [baseUrl, apiUrl, statePath, evidenceDir, output] = process.argv.slice(2);
const state = JSON.parse(await readFile(statePath, 'utf8'));
await mkdir(evidenceDir, { recursive: true });
const results = [];

async function api(route) {
  const response = await fetch(`${apiUrl}${route}`);
  const text = await response.text();
  return { status: response.status, data: text ? JSON.parse(text) : null };
}

async function check(id, hypothesis, fn) {
  try {
    const detail = await fn();
    results.push({ id, hypothesis, result: 'PASS', detail });
    console.log(`PASS ${id}`);
  } catch (error) {
    results.push({ id, hypothesis, result: 'FAIL', error: error.message });
    console.log(`FAIL ${id}: ${error.message}`);
  }
}

const browser = await chromium.launch({ headless: true });
const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });

await check('REST-01', 'API counts and analyst detail are unchanged after a backend restart on the same SQLite file.', async () => {
  const counts = {
    total: (await api('/api/cases')).data.totalElements,
    cfpb: (await api('/api/cases?source=CFPB')).data.totalElements,
    analyst: (await api('/api/cases?source=ANALYST')).data.totalElements,
  };
  assert.deepEqual(counts, { total: state.total, cfpb: state.cfpb, analyst: state.analyst });
  const detail = (await api(`/api/cases/${state.analystId}`)).data;
  assert.equal(detail.narrative, state.narrative);
  assert.equal(detail.analystNotes.handlingNotes, state.handlingNotes);
  assert.equal(detail.analystNotes.evaluationNotes, state.evaluationNotes);
  assert.equal(detail.analystNotes.outcomeAssessment, state.outcomeAssessment);
  assert.deepEqual(detail.referenceCaseIds, state.referenceIds);
  return { counts, analystId: state.analystId };
});

await check('REST-02', 'Analyst detail, references, and metadata render from persisted data in the browser.', async () => {
  await page.goto(`${baseUrl}/cases/${state.analystId}`, { waitUntil: 'domcontentloaded' });
  await page.getByRole('heading', { name: 'Complaint narrative' }).waitFor({ timeout: 30_000 });
  await page.getByText(state.handlingNotes).waitFor();
  await page.getByText(state.evaluationNotes).waitFor();
  const referenceLinks = await page.getByRole('heading', { name: 'References' })
    .locator('..').locator('a[href^="/cases/"]').count();
  assert.equal(referenceLinks, state.referenceIds.length);
  await page.screenshot({ path: path.join(evidenceDir, 'post-restart-analyst-detail.png'), fullPage: true });
  return { referenceLinks };
});

await check('REST-03', 'Case library still exposes the full corpus and the ANALYST filter after restart.', async () => {
  await page.goto(`${baseUrl}/cases`, { waitUntil: 'domcontentloaded' });
  await page.getByRole('heading', { name: 'Case library' }).waitFor();
  await page.getByLabel('Source', { exact: true }).selectOption('ANALYST');
  const expected = `${state.analyst} case${state.analyst === 1 ? '' : 's'} in this source · 20 per page`;
  await page.getByText(expected).waitFor({ timeout: 20_000 });
  await page.screenshot({ path: path.join(evidenceDir, 'post-restart-library-analyst.png'), fullPage: true });
  return { expected };
});

await check('REST-04', 'Search still returns the retained analyst case by its narrative after restart.', async () => {
  await page.goto(`${baseUrl}/`, { waitUntil: 'domcontentloaded' });
  await page.getByLabel('Product', { exact: true }).selectOption({ label: 'Checking or savings account' });
  await page.getByLabel('Issue', { exact: true }).selectOption({ label: 'Managing an account' });
  await page.locator('#search-sub-issue').selectOption({ label: 'Deposits and withdrawals' });
  await page.getByLabel('Complaint narrative', { exact: true }).fill(state.narrative);
  await page.getByRole('button', { name: 'Find precedents', exact: true }).click();
  await page.getByRole('heading', { name: 'Precedent matches' }).waitFor({ timeout: 30_000 });
  const card = page.locator('article').filter({ hasText: state.narrative.slice(0, 30) });
  await card.first().waitFor();
  await page.screenshot({ path: path.join(evidenceDir, 'post-restart-search.png'), fullPage: true });
  return { foundAnalystCase: true };
});

await check('REST-05', 'CFPB archive case detail still renders provenance after restart.', async () => {
  await page.goto(`${baseUrl}/cases/${state.cfpbId}`, { waitUntil: 'domcontentloaded' });
  await page.getByRole('heading', { name: 'Complaint narrative' }).waitFor({ timeout: 30_000 });
  await page.getByText('CFPB provenance').waitFor();
  await page.screenshot({ path: path.join(evidenceDir, 'post-restart-cfpb-detail.png'), fullPage: true });
  return { cfpbId: state.cfpbId };
});

await browser.close();
const summary = {
  scenarios: results.length,
  passed: results.filter((item) => item.result === 'PASS').length,
  failed: results.filter((item) => item.result === 'FAIL').length,
};
await writeFile(output, `${JSON.stringify({ session: 'browser-restart', summary, results }, null, 2)}\n`);
console.log(JSON.stringify({ output, ...summary }));
if (summary.failed) process.exitCode = 1;
