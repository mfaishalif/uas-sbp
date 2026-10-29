// Exploratory check: browser maxlength counts UTF-16 units but the contract
// counts Unicode code points. Uses simulated text insertion (not value-setting)
// so the browser's own maxlength behavior is exercised.
// Usage: node emoji-input-check.mjs <baseUrl> <apiUrl> <evidenceDir> <output.json>
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

const narrative = 'transfer ' + '😀'.repeat(26_000); // 26,009 code points, 52,009 UTF-16 units
const response = await fetch(`${apiUrl}/api/search`, {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({
    product: 'Checking or savings account',
    issue: 'Managing an account',
    narrative,
    limit: 5,
  }),
});
const apiBody = await response.json();

await check('EMOJI-API', 'The API accepts a 26,009-code-point narrative below the 50,000 limit.', async () => {
  assert.equal(response.status, 200);
  return { status: response.status, codePoints: Array.from(narrative).length, utf16Units: narrative.length };
});

await check('EMOJI-UI', 'The search textarea accepts the same 26,009-code-point narrative under its 50,000-character limit.', async () => {
  await page.goto(baseUrl, { waitUntil: 'domcontentloaded' });
  const textarea = page.getByLabel('Complaint narrative', { exact: true });
  await textarea.waitFor({ timeout: 30_000 });
  await textarea.focus();
  await page.keyboard.insertText(narrative);
  const value = await textarea.inputValue();
  const codePoints = Array.from(value).length;
  const utf16Units = value.length;
  const counter = await page.getByText(/\/ 50,000 characters/).textContent();
  await page.screenshot({ path: path.join(evidenceDir, 'emoji-narrative-limit.png'), fullPage: true });
  assert.ok(
    codePoints >= Array.from(narrative).length,
    `textarea kept ${codePoints} code points of ${Array.from(narrative).length}; counter=${counter}`,
  );
  return { codePoints, utf16Units, counter };
});

await browser.close();
const summary = {
  scenarios: results.length,
  passed: results.filter((item) => item.result === 'PASS').length,
  failed: results.filter((item) => item.result === 'FAIL').length,
};
await writeFile(output, `${JSON.stringify({
  session: 'emoji-input',
  summary,
  apiStatus: response.status,
  apiBody: { status: apiBody.status, code: apiBody.code },
  results,
}, null, 2)}\n`);
console.log(JSON.stringify({ output, ...summary }));
if (summary.failed) process.exitCode = 1;
