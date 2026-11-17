import { test, expect, type Page } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';

const NARRATIVE =
  'Money left my bank balance twice even though I only authorized one payment.';
const NOTES = 'Reviewed the duplicate authorization and requested a refund of the second charge.';

test.describe.configure({ mode: 'serial' });

async function expectNoContrastViolations(page: Page) {
  const result = await new AxeBuilder({ page }).withRules(['color-contrast']).analyze();
  expect(
    result.violations,
    result.violations.map((violation) => `${violation.id}: ${violation.help}`).join('\n'),
  ).toEqual([]);
}

async function expectNoHorizontalOverflow(page: Page) {
  const overflow = await page.evaluate(
    () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
  );
  expect(overflow).toBeLessThanOrEqual(1);
}

async function searchComplaint(page: Page) {
  await page.getByLabel('Product', { exact: true }).selectOption({ label: 'Checking or savings account' });
  await page.getByLabel('Issue', { exact: true }).selectOption({ label: 'Managing an account' });
  await page.locator('#search-sub-issue').selectOption({ label: 'Deposits and withdrawals' });
  await page.getByLabel('Complaint narrative', { exact: true }).fill(NARRATIVE);
  await page.locator('#search-form').getByRole('button', { name: 'Find precedents' }).click();
}

test('search, take the recommendation, save, and find the case in the library', async ({ page }) => {
  await page.goto('/');
  await expect(page.getByRole('heading', { level: 1 })).toHaveCount(1);
  await expect(page.getByText(/Showing the default configuration/)).toBeVisible();

  await searchComplaint(page);

  const results = page.getByRole('region', { name: 'Closest precedents' });
  await expect(results.getByRole('article')).toHaveCount(3);
  await expect(results.getByText('Total score')).toHaveCount(3);
  await expect(page.getByText(/Live configuration from your last search/)).toBeVisible();

  const panel = page.getByRole('region', { name: 'Response recommendation' });
  await expect(panel.getByText('Recommended', { exact: true })).toHaveCount(1);
  const responseSelect = page.getByLabel('Company response', { exact: true });
  const recommended = await responseSelect.inputValue();
  expect(recommended).toMatch(/^Closed with /);
  await expect(responseSelect.locator('option:checked')).toHaveText(`${recommended} (Recommended)`);

  await expectNoContrastViolations(page);

  // The precedent detail opens in a modal and returns focus to its trigger.
  const firstCard = results.getByRole('article').first();
  const viewButton = firstCard.getByRole('button', { name: /View full case/ });
  await viewButton.click();
  await expect(page.getByRole('dialog')).toBeVisible();
  await page.keyboard.press('Escape');
  await expect(page.getByRole('dialog')).toHaveCount(0);
  await expect(viewButton).toBeFocused();

  await page.getByLabel('Handling notes', { exact: true }).fill(NOTES);
  await page.getByRole('button', { name: 'Save case' }).click();
  await expect(page.getByText('Case saved to the library')).toBeVisible();
  await expect(page.getByText(/with 3 references/)).toBeVisible();
  await expect(page.getByLabel('Complaint narrative', { exact: true })).toHaveValue('');

  // The saved case is now a search candidate.
  await searchComplaint(page);
  await expect(results.getByRole('article')).toHaveCount(3);
  await expect(results.getByRole('article').getByText('Analyst', { exact: true }).first()).toBeVisible();

  // It is in the library as an Analyst case with its references.
  await page.goto('/cases');
  await page.getByRole('radio', { name: 'Analyst' }).check();
  const card = page.getByRole('article').first();
  await expect(card.getByText('Analyst', { exact: true })).toBeVisible();
  const trigger = card.getByRole('button', { name: /Checking or savings account/ });
  await trigger.click();
  const dialog = page.getByRole('dialog');
  await expect(dialog.getByText(NOTES)).toBeVisible();
  await expect(dialog.getByRole('heading', { name: 'References' })).toBeVisible();
  const references = dialog.locator('ul li button');
  await expect(references).toHaveCount(3);
  await references.first().click();
  await expect(dialog.getByRole('button', { name: /Back to previous case/ })).toBeVisible();
  await expect(dialog.getByText(/CFPB complaint \d+/)).toBeVisible();
  await page.keyboard.press('Escape');
  await expect(dialog).toHaveCount(0);
  await expect(trigger).toBeFocused();

  await expectNoContrastViolations(page);
});

test('layout holds at desktop and phone widths', async ({ browser }) => {
  for (const viewport of [{ width: 1440, height: 1000 }, { width: 390, height: 844 }]) {
    const page = await browser.newPage({ viewport });
    await page.goto('/');
    await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
    await expectNoHorizontalOverflow(page);
    await expect(
      page.getByRole('navigation', { name: 'Main navigation' }).getByRole('link', { name: 'Case library' }),
    ).toBeVisible();

    await page.goto('/cases');
    await expect(page.getByRole('heading', { level: 1, name: 'Case library' })).toBeVisible();
    await expectNoHorizontalOverflow(page);
    await page.close();
  }
});

test('the skip link is the first tab stop and moves to main content', async ({ page }) => {
  await page.goto('/');
  await page.keyboard.press('Tab');
  const skipLink = page.locator('.skip-link');
  await expect(skipLink).toBeFocused();
  const box = await skipLink.boundingBox();
  expect(box?.y ?? -1).toBeGreaterThanOrEqual(0);
  await page.keyboard.press('Enter');
  await expect(page).toHaveURL(/#main-content$/);
});

test('removed routes answer with the not-found page', async ({ page }) => {
  for (const path of ['/imports', '/cases/new']) {
    const response = await page.goto(path);
    expect(response?.status()).toBe(404);
    await expect(page.getByRole('heading', { name: 'Page not found' })).toBeVisible();
  }
});
