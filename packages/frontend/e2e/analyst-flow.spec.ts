import fs from 'node:fs';
import path from 'node:path';
import { test, expect } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';

const sampleCsv = path.resolve(import.meta.dirname, '../../../data/cfpb/sample.csv');
const archiveUrl =
  'https://files.consumerfinance.gov/f/documents/CCDB_Export_8_November_2024_through_December_2024.zip';

async function fillImportMetadata(
  page: import('@playwright/test').Page,
  sourceFileName: string,
) {
  await page.getByLabel('Archive URL', { exact: true }).fill(archiveUrl);
  await page.getByLabel('Source file name', { exact: true }).fill(sourceFileName);
  await page.getByLabel('Period start', { exact: true }).fill('2024-11-01');
  await page.getByLabel('Period end', { exact: true }).fill('2024-12-31');
}

test.describe.configure({ mode: 'serial' });

test('import, search, select reference, retain, view, and edit in the browser', async ({
  page,
  browser,
}) => {
  await page.goto('/imports');
  await expect(
    page.getByRole('heading', { name: 'Import CFPB archive data' }),
  ).toBeVisible();

  await page.getByLabel('CSV file', { exact: true }).setInputFiles(sampleCsv);
  await fillImportMetadata(page, 'finresolve-sample.csv');
  await page.getByRole('button', { name: 'Import CSV' }).click();

  await expect(
    page.getByRole('heading', { name: /Import completed|Replay detected/ }),
  ).toBeVisible({ timeout: 120_000 });
  await expect(page.getByText('Processed', { exact: true }).locator('..')).toContainText('500');
  await expect(page.getByText('Imported', { exact: true }).locator('..')).toContainText('500');

  await page
    .getByRole('navigation', { name: 'Main navigation' })
    .getByRole('link', { name: 'Find precedents' })
    .click();
  await expect(
    page.getByRole('heading', { name: 'Find context for your next complaint' }),
  ).toBeVisible();
  await page.getByLabel('Product', { exact: true }).selectOption({ label: 'Checking or savings account' });
  await page.getByLabel('Issue', { exact: true }).selectOption({ label: 'Managing an account' });
  await page
    .locator('#search-sub-issue')
    .selectOption({ label: 'Deposits and withdrawals' });
  await page.getByLabel('Complaint narrative', { exact: true }).fill(
    'Money left my bank balance twice even though I only authorized one payment.',
  );
  await page.getByRole('button', { name: 'Find precedents' }).click();

  await expect(page.getByText('Similarity score').first()).toBeVisible();
  await expect(page.getByText(/actual server configuration/i)).toBeVisible();
  await expect(page.getByText(/tfidf-/).first()).toBeVisible();

  const resultsAccessibility = await new AxeBuilder({ page })
    .withRules(['color-contrast'])
    .analyze();
  expect(
    resultsAccessibility.violations,
    resultsAccessibility.violations
      .map((violation) => `${violation.id}: ${violation.help}`)
      .join('\n'),
  ).toEqual([]);

  const firstCard = page.locator('article').filter({ hasText: 'Similarity score' }).first();
  await firstCard.getByRole('button', { name: 'Use as reference' }).click();
  await expect(
    firstCard.getByRole('button', { name: 'Remove reference' }),
  ).toHaveAttribute('aria-pressed', 'true');

  // Client-side navigation must keep the search results in memory.
  await firstCard.getByRole('link', { name: 'View full case' }).click();
  await expect(page.getByRole('heading', { name: 'Complaint narrative' })).toBeVisible();
  await page.goBack();
  await expect(page.getByText('Similarity score').first()).toBeVisible();
  await expect(page.getByRole('button', { name: 'Remove reference' }).first()).toBeVisible();

  await page.getByRole('button', { name: 'Continue to retention' }).click();
  await expect(page).toHaveURL(/\/cases\/new$/);
  expect(new URL(page.url()).search).toBe('');
  await expect(page.getByLabel('Complaint narrative', { exact: true })).toHaveValue(
    'Money left my bank balance twice even though I only authorized one payment.',
  );

  await page.getByLabel('Handling notes', { exact: true }).fill(
    'Reviewed the duplicate authorization and requested a transfer trace.',
  );
  await page.locator('#case-company').fill('E2E Example Bank');
  await page.locator('#case-evaluation-notes').fill('Initial analyst evaluation.');
  await page.getByRole('button', { name: 'Save analyst case' }).click();

  await expect(page).toHaveURL(/\/cases\/[0-9a-f-]{36}$/);
  await expect(page.getByText(/analyst notes/i)).toBeVisible();
  await expect(
    page.getByText('Reviewed the duplicate authorization and requested a transfer trace.'),
  ).toBeVisible();
  await expect(page.getByRole('heading', { name: 'References' })).toBeVisible();
  const referenceLink = page.getByRole('heading', { name: 'References' })
    .locator('..')
    .locator('a[href^="/cases/"]')
    .first();
  await expect(referenceLink).toBeVisible();

  await page.getByRole('link', { name: 'Edit case' }).click();
  await expect(page.getByRole('heading', { name: 'Edit analyst case' })).toBeVisible();
  await expect(page.getByLabel('Handling notes', { exact: true })).toHaveValue(
    'Reviewed the duplicate authorization and requested a transfer trace.',
  );
  await page.locator('#case-evaluation-notes').fill('E2E evaluation after editing.');
  await page.getByLabel('Outcome assessment', { exact: true }).selectOption('RESOLVED');
  await page.getByRole('button', { name: 'Save changes' }).click();

  await expect(page).toHaveURL(/\/cases\/[0-9a-f-]{36}$/);
  await expect(page.getByText('E2E evaluation after editing.')).toBeVisible();
  await expect(page.getByText('Resolved', { exact: true })).toBeVisible();

  const detailAccessibility = await new AxeBuilder({ page })
    .withRules(['color-contrast'])
    .analyze();
  expect(
    detailAccessibility.violations,
    detailAccessibility.violations
      .map((violation) => `${violation.id}: ${violation.help}`)
      .join('\n'),
  ).toEqual([]);

  // A CFPB archive case opens, but its edit route is read-only. Use a separate
  // tab so the analyst flow's in-memory search state stays intact.
  const cfpbHref = await referenceLink.getAttribute('href');
  expect(cfpbHref).toBeTruthy();
  const cfpbPage = await browser.newPage();
  await cfpbPage.goto(`${cfpbHref}/edit`);
  await expect(
    cfpbPage.getByRole('heading', { name: 'This CFPB case cannot be edited' }),
  ).toBeVisible();
  await expect(cfpbPage.getByLabel('Handling notes', { exact: true })).toHaveCount(0);
  await cfpbPage.close();

  // Return through client navigation so the previous search remains in memory.
  await page
    .getByRole('navigation', { name: 'Main navigation' })
    .getByRole('link', { name: 'Find precedents' })
    .click();
  await expect(page.getByText('Similarity score').first()).toBeVisible();

  await page.setViewportSize({ width: 390, height: 844 });
  await expect(
    page.getByRole('heading', { name: 'Find context for your next complaint' }),
  ).toBeVisible();
  const layoutColumns = await page
    .getByTestId('search-layout')
    .evaluate((element) => getComputedStyle(element).gridTemplateColumns);
  expect(layoutColumns.split(' ')).toHaveLength(1);
  const overflow = await page.evaluate(
    () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
  );
  expect(overflow).toBeLessThanOrEqual(1);

  // Keyboard: the first Tab reaches the skip link and moves it into view.
  await page.goto('/');
  await page.keyboard.press('Tab');
  const skipLink = page.locator('.skip-link');
  await expect(skipLink).toBeFocused();
  const box = await skipLink.boundingBox();
  expect(box?.y ?? -1).toBeGreaterThanOrEqual(0);
  await page.keyboard.press('Enter');
  await expect(page).toHaveURL(/#main-content$/);

  const accessibility = await new AxeBuilder({ page })
    .withRules(['color-contrast'])
    .analyze();
  expect(
    accessibility.violations,
    accessibility.violations
      .map((violation) => `${violation.id}: ${violation.help}`)
      .join('\n'),
  ).toEqual([]);

  // Open a second page to make sure mobile navigation itself is usable.
  const mobile = await browser.newPage({ viewport: { width: 390, height: 844 } });
  await mobile.goto('/');
  await expect(
    mobile
      .getByRole('navigation', { name: 'Main navigation' })
      .getByRole('link', { name: 'Case library' }),
  ).toBeVisible();
  const mobileOverflow = await mobile.evaluate(
    () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
  );
  expect(mobileOverflow).toBeLessThanOrEqual(1);
  await mobile.close();
});

test('uploads a near-limit CSV through the Next.js proxy', async ({ page }, testInfo) => {
  test.setTimeout(240_000);
  const nearLimit = testInfo.outputPath('near-limit.csv');
  const targetBytes = 19 * 1024 * 1024;
  const header =
    'Complaint ID,Product,Sub-product,Issue,Sub-issue,Consumer complaint narrative,Company,Company response to consumer,Date received\n';
  const complaintId = `${Date.now()}${Math.floor(Math.random() * 10_000)}`.slice(0, 15);
  const row = `${complaintId},"Checking or savings account",,Managing an account,,"A duplicate card payment was charged twice and the second charge was not refunded.",E2E Near Limit Bank,Closed with explanation,2024-11-15\n`;
  const padding = '\n'.repeat(Math.max(0, targetBytes - header.length - row.length));
  fs.writeFileSync(nearLimit, `${header}${row}${padding}`, 'utf8');

  await page.goto('/imports');
  await page.getByLabel('CSV file', { exact: true }).setInputFiles(nearLimit);
  await fillImportMetadata(page, 'near-limit.csv');
  await page.getByRole('button', { name: 'Import CSV' }).click();

  await expect(
    page.getByRole('heading', { name: /Import completed|Replay detected/ }),
  ).toBeVisible({ timeout: 180_000 });
  await expect(page.getByText('Processed', { exact: true }).locator('..')).toContainText('1');
  await expect(page.getByText('Imported', { exact: true }).locator('..')).toContainText('1');
});
