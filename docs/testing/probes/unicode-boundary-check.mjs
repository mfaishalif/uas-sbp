// FS-01 regression probe: the HTML controls must not truncate astral input and
// the application must enforce its limits in Unicode code points at submit time.
//
// Text is inserted through the browser (keyboard.insertText), not by assigning
// element.value, so native maxlength behaviour would be observed if it returned.
//
// Usage:
//   node unicode-boundary-check.mjs <baseUrl> <apiUrl> <evidenceDir> <output.json>
import assert from 'node:assert/strict';
import { mkdir, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { chromium } from 'playwright';

const [baseUrl, apiUrl, evidenceDir, output] = process.argv.slice(2);
if (!baseUrl || !apiUrl || !evidenceDir || !output) {
  throw new Error('usage: unicode-boundary-check.mjs <baseUrl> <apiUrl> <evidenceDir> <output.json>');
}
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

const codePoints = (value) => Array.from(value).length;

/** Builds exactly `limit` code points; the narrative variants keep one ASCII token. */
function makeText(kind, limit, withToken) {
  const prefix = withToken ? 'transfer ' : '';
  const unit = kind === 'BMP' ? 'x' : '😀';
  return prefix + unit.repeat(limit - codePoints(prefix));
}

async function waitForCodePoints(locator, expected, selector) {
  await locator.page().waitForFunction(
    ({ selector: currentSelector, expected: currentExpected }) => {
      const element = document.querySelector(currentSelector);
      return element instanceof HTMLInputElement || element instanceof HTMLTextAreaElement
        ? Array.from(element.value).length === currentExpected
        : false;
    },
    { selector, expected },
    { timeout: 60_000 },
  );
  const value = await locator.inputValue();
  assert.equal(codePoints(value), expected);
  return value;
}

async function insertText(page, locator, selector, value) {
  await locator.click();
  await page.keyboard.press('ControlOrMeta+A');
  await page.keyboard.press('Backspace');
  await page.keyboard.insertText(value);
  return waitForCodePoints(locator, codePoints(value), selector);
}

const browser = await chromium.launch({ headless: true });
const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
const searchRequests = [];
const createRequests = [];
page.on('request', (request) => {
  if (request.method() !== 'POST') return;
  if (request.url().includes('/api/search')) searchRequests.push(request.url());
  if (request.url().includes('/api/cases')) createRequests.push(request.url());
});

async function getCase(id) {
  const response = await fetch(`${apiUrl}/api/cases/${id}`);
  assert.equal(response.status, 200);
  return response.json();
}

async function openSearch() {
  await page.goto(baseUrl, { waitUntil: 'domcontentloaded' });
  await page.waitForFunction(
    () => document.querySelectorAll('#search-product option').length > 1,
    undefined,
    { timeout: 30_000 },
  );
  await page.locator('#search-product').selectOption({ label: 'Checking or savings account' });
  await page.locator('#search-issue').selectOption({ label: 'Managing an account' });
}

async function openNewCase() {
  await page.goto(`${baseUrl}/cases/new`, { waitUntil: 'domcontentloaded' });
  await page.waitForFunction(
    () => document.querySelectorAll('#case-product option').length > 1,
    undefined,
    { timeout: 30_000 },
  );
  await page.locator('#case-product').selectOption({ label: 'Checking or savings account' });
  await page.locator('#case-issue').selectOption({ label: 'Managing an account' });
}

const SEARCH = {
  id: 'search-narrative',
  label: 'Complaint narrative',
  limit: 50_000,
  error: 'The narrative must be 50,000 characters or fewer.',
  token: true,
};
const CASE_FIELDS = [
  {
    id: 'case-narrative',
    label: 'Complaint narrative',
    detail: (detail) => detail.narrative,
    limit: 50_000,
    error: 'The narrative must be 50,000 characters or fewer.',
    token: true,
    required: true,
  },
  {
    id: 'case-handling-notes',
    label: 'Handling notes',
    detail: (detail) => detail.analystNotes.handlingNotes,
    limit: 10_000,
    error: 'Handling notes must be 10,000 characters or fewer.',
    token: false,
    required: true,
  },
  {
    id: 'case-evaluation-notes',
    label: 'Evaluation notes',
    detail: (detail) => detail.analystNotes.evaluationNotes,
    limit: 10_000,
    error: 'Evaluation notes must be 10,000 characters or fewer.',
    token: false,
    required: false,
  },
  {
    id: 'case-company',
    label: 'Company',
    detail: (detail) => detail.company,
    limit: 500,
    error: 'Company must be 500 characters or fewer.',
    token: false,
    required: false,
  },
  {
    id: 'case-company-response',
    label: 'Company response',
    detail: (detail) => detail.companyResponse,
    limit: 2_000,
    error: 'Company response must be 2,000 characters or fewer.',
    token: false,
    required: false,
  },
];

// --- Search narrative: both character classes at the boundary and boundary+1.
for (const kind of ['BMP', 'astral']) {
  const atLimit = makeText(kind, SEARCH.limit, SEARCH.token);
  const overLimit = `${atLimit}${kind === 'BMP' ? 'x' : '😀'}`;

  await check(`SEARCH-${kind}-AT-LIMIT`, `${kind} input with exactly ${SEARCH.limit} code points is sent intact.`, async () => {
    await openSearch();
    const locator = page.locator(`#${SEARCH.id}`);
    const value = await insertText(page, locator, `#${SEARCH.id}`, atLimit);
    assert.equal(value, atLimit);
    const before = searchRequests.length;
    const responsePromise = page.waitForResponse(
      (response) => response.url().includes('/api/search') && response.request().method() === 'POST',
      { timeout: 120_000 },
    );
    await page.getByRole('button', { name: 'Find precedents' }).click();
    const response = await responsePromise;
    assert.equal(response.status(), 200);
    assert.equal(searchRequests.length, before + 1);
    assert.equal(codePoints(await locator.inputValue()), SEARCH.limit);
    if (kind === 'astral') {
      await page.screenshot({ path: path.join(evidenceDir, 'unicode-boundary-accepted.png'), fullPage: true });
    }
    return { codePoints: codePoints(value), utf16Units: value.length };
  });

  await check(`SEARCH-${kind}-OVER-LIMIT`, `${kind} input with ${SEARCH.limit + 1} code points is rejected before any API call and stays intact.`, async () => {
    await openSearch();
    const locator = page.locator(`#${SEARCH.id}`);
    const value = await insertText(page, locator, `#${SEARCH.id}`, overLimit);
    assert.equal(value, overLimit);
    const before = searchRequests.length;
    await page.getByRole('button', { name: 'Find precedents' }).click();
    await page.getByText(SEARCH.error, { exact: true }).waitFor({ timeout: 30_000 });
    await page.waitForTimeout(300);
    assert.equal(searchRequests.length, before, 'search must not reach the API');
    assert.equal(codePoints(await locator.inputValue()), SEARCH.limit + 1);
    if (kind === 'astral') {
      await page.screenshot({ path: path.join(evidenceDir, 'unicode-boundary-error.png'), fullPage: true });
    }
    return { codePoints: codePoints(value), utf16Units: value.length };
  });
}

// --- Every case-form field: boundary accepted and round-tripped, boundary+1 refused.
for (const field of CASE_FIELDS) {
  for (const kind of ['BMP', 'astral']) {
    const atLimit = makeText(kind, field.limit, field.token);
    const overLimit = `${atLimit}${kind === 'BMP' ? 'x' : '😀'}`;

    await check(`CASE-${field.id}-${kind}-AT-LIMIT`, `${field.label} accepts exactly ${field.limit} ${kind} code points and preserves them through create and GET.`, async () => {
      await openNewCase();
      if (field.id !== 'case-narrative') {
        await insertText(page, page.locator('#case-narrative'), '#case-narrative', 'A valid narrative about a transfer.');
      }
      if (field.id !== 'case-handling-notes') {
        await insertText(page, page.locator('#case-handling-notes'), '#case-handling-notes', 'Handled the transfer request.');
      }
      const locator = page.locator(`#${field.id}`);
      const value = await insertText(page, locator, `#${field.id}`, atLimit);
      assert.equal(value, atLimit);

      const before = createRequests.length;
      await page.getByRole('button', { name: 'Save analyst case' }).click();
      await page.waitForURL(/\/cases\/[0-9a-f-]{36}$/, { timeout: 120_000 });
      assert.equal(createRequests.length, before + 1);
      const id = page.url().split('/').pop();
      const detail = await getCase(id);
      assert.equal(field.detail(detail), atLimit);
      assert.equal(codePoints(field.detail(detail)), field.limit);
      return { id, codePoints: field.limit, utf16UnitsAtLimit: atLimit.length };
    });

    await check(`CASE-${field.id}-${kind}-OVER-LIMIT`, `${field.label} refuses ${field.limit + 1} ${kind} code points without an API call and without truncation.`, async () => {
      await openNewCase();
      if (field.id !== 'case-narrative') {
        await insertText(page, page.locator('#case-narrative'), '#case-narrative', 'A valid narrative about a transfer.');
      }
      if (field.id !== 'case-handling-notes') {
        await insertText(page, page.locator('#case-handling-notes'), '#case-handling-notes', 'Handled the transfer request.');
      }
      const locator = page.locator(`#${field.id}`);
      const value = await insertText(page, locator, `#${field.id}`, overLimit);
      assert.equal(value, overLimit);

      const before = createRequests.length;
      await page.getByRole('button', { name: 'Save analyst case' }).click();
      await page.getByText(field.error, { exact: true }).waitFor({ timeout: 30_000 });
      await page.waitForTimeout(300);
      assert.equal(createRequests.length, before, 'case creation must not reach the API');
      assert.equal(codePoints(await locator.inputValue()), field.limit + 1);
      return { codePoints: codePoints(value), utf16Units: value.length };
    });
  }
}

// --- Unicode round-trip through create -> GET -> edit form -> PUT -> GET.
const roundTrip = {
  narrative: 'Round-trip 😀 café 𝄞 漢字 transfer.',
  company: 'Example Bank 😀',
  companyResponse: 'Response contains 𝄞 and 漢字.',
  handlingNotes: 'Handled with 😀 evidence.',
  evaluationNotes: 'Initial evaluation 𝄞.',
};
let roundTripId;
await check('ROUNDTRIP-CREATE-GET', 'Unicode values survive create and a fresh GET.', async () => {
  await openNewCase();
  await insertText(page, page.locator('#case-narrative'), '#case-narrative', roundTrip.narrative);
  await insertText(page, page.locator('#case-company'), '#case-company', roundTrip.company);
  await insertText(page, page.locator('#case-company-response'), '#case-company-response', roundTrip.companyResponse);
  await insertText(page, page.locator('#case-handling-notes'), '#case-handling-notes', roundTrip.handlingNotes);
  await insertText(page, page.locator('#case-evaluation-notes'), '#case-evaluation-notes', roundTrip.evaluationNotes);
  await page.getByRole('button', { name: 'Save analyst case' }).click();
  await page.waitForURL(/\/cases\/[0-9a-f-]{36}$/, { timeout: 120_000 });
  roundTripId = page.url().split('/').pop();
  const detail = await getCase(roundTripId);
  assert.equal(detail.narrative, roundTrip.narrative);
  assert.equal(detail.company, roundTrip.company);
  assert.equal(detail.companyResponse, roundTrip.companyResponse);
  assert.equal(detail.analystNotes.handlingNotes, roundTrip.handlingNotes);
  assert.equal(detail.analystNotes.evaluationNotes, roundTrip.evaluationNotes);
  return { id: roundTripId };
});

await check('ROUNDTRIP-EDIT-LOAD', 'The edit form loads the same Unicode values unchanged.', async () => {
  await page.goto(`${baseUrl}/cases/${roundTripId}/edit`, { waitUntil: 'domcontentloaded' });
  await page.waitForFunction(
    (id) => document.querySelector(id) instanceof HTMLInputElement
      && document.querySelector(id).value === 'Example Bank 😀',
    '#case-company',
    { timeout: 30_000 },
  );
  assert.equal(await page.locator('#case-narrative').inputValue(), roundTrip.narrative);
  assert.equal(await page.locator('#case-company').inputValue(), roundTrip.company);
  assert.equal(await page.locator('#case-company-response').inputValue(), roundTrip.companyResponse);
  assert.equal(await page.locator('#case-handling-notes').inputValue(), roundTrip.handlingNotes);
  assert.equal(await page.locator('#case-evaluation-notes').inputValue(), roundTrip.evaluationNotes);
});

const editedEvaluation = 'Edited evaluation after round-trip 😀 𝄞.';
await check('ROUNDTRIP-EDIT-GET', 'An edit preserves the other Unicode fields and stores the new value.', async () => {
  await insertText(page, page.locator('#case-evaluation-notes'), '#case-evaluation-notes', editedEvaluation);
  await page.getByRole('button', { name: 'Save changes' }).click();
  await page.waitForURL(/\/cases\/[0-9a-f-]{36}$/, { timeout: 120_000 });
  const detail = await getCase(roundTripId);
  assert.equal(detail.narrative, roundTrip.narrative);
  assert.equal(detail.company, roundTrip.company);
  assert.equal(detail.companyResponse, roundTrip.companyResponse);
  assert.equal(detail.analystNotes.handlingNotes, roundTrip.handlingNotes);
  assert.equal(detail.analystNotes.evaluationNotes, editedEvaluation);
  return { id: roundTripId };
});

await browser.close();
const summary = {
  scenarios: results.length,
  passed: results.filter((item) => item.result === 'PASS').length,
  failed: results.filter((item) => item.result === 'FAIL').length,
};
await writeFile(output, `${JSON.stringify({
  session: 'unicode-boundary',
  baseUrl,
  apiUrl,
  summary,
  results,
}, null, 2)}\n`);
console.log(JSON.stringify({ output, ...summary }));
if (summary.failed) process.exitCode = 1;
