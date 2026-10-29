// Phrase-mode UI verification after restarting the backend with phrases mode.
// Usage: node browser-phrases-check.mjs <baseUrl> <apiUrl> <evidenceDir> <output.json>
import assert from 'node:assert/strict';
import { writeFile, mkdir } from 'node:fs/promises';
import path from 'node:path';
import { chromium } from 'playwright';

const [baseUrl, apiUrl, evidenceDir, output] = process.argv.slice(2);
await mkdir(evidenceDir, { recursive: true });
const results = [];

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

await check('PHR-UI-01', 'Search response and UI report tfidf-phrases-v2 when the server runs in phrases mode.', async () => {
  await page.goto(`${baseUrl}/`, { waitUntil: 'domcontentloaded' });
  await page.getByLabel('Product', { exact: true }).selectOption({ label: 'Checking or savings account' });
  await page.getByLabel('Issue', { exact: true }).selectOption({ label: 'Managing an account' });
  await page.locator('#search-sub-issue').selectOption({ label: 'Deposits and withdrawals' });
  await page.getByLabel('Complaint narrative', { exact: true }).fill(
    'The cash machine charged twice and the funds held were released.',
  );
  await page.getByRole('button', { name: 'Find precedents', exact: true }).click();
  await page.getByRole('heading', { name: 'Precedent matches' }).waitFor({ timeout: 30_000 });
  const body = await page.locator('body').textContent();
  assert.match(body, /tfidf-phrases-v2/);
  assert.doesNotMatch(body, /tfidf-cosine-v1/);
  const shared = await page.locator('article').first().textContent();
  assert.match(shared, /shared terms/i);
  const response = await fetch(`${apiUrl}/api/search`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      product: 'Checking or savings account',
      subProduct: 'Checking account',
      issue: 'Managing an account',
      subIssue: 'Deposits and withdrawals',
      narrative: 'The cash machine charged twice and the funds held were released.',
      limit: 5,
    }),
  });
  const data = await response.json();
  assert.equal(response.status, 200);
  assert.equal(data.configuration.algorithmVersion, 'tfidf-phrases-v2');
  await page.screenshot({ path: path.join(evidenceDir, 'phrases-search-results.png'), fullPage: true });
  return { algorithm: data.configuration.algorithmVersion, results: data.returnedCount };
});

await check('PHR-UI-02', 'Baseline wording is absent from the configuration panel in phrases mode.', async () => {
  const config = await page.getByRole('heading', { name: 'Actual server configuration' }).locator('..').textContent();
  assert.match(config, /tfidf-phrases-v2/);
  assert.doesNotMatch(config, /tfidf-cosine-v1/);
  return { config };
});

await browser.close();
const summary = {
  scenarios: results.length,
  passed: results.filter((item) => item.result === 'PASS').length,
  failed: results.filter((item) => item.result === 'FAIL').length,
};
await writeFile(output, `${JSON.stringify({ session: 'browser-phrases', summary, results }, null, 2)}\n`);
console.log(JSON.stringify({ output, ...summary }));
if (summary.failed) process.exitCode = 1;
