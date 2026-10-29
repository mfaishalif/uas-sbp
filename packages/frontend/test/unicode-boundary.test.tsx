import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { CaseForm } from '@/components/cases/CaseForm';
import { FindPrecedentsPage } from '@/components/search/FindPrecedentsPage';
import { AppProviders } from '@/contexts/AppProviders';
import { emptyCaseForm } from '@/lib/case';
import { categoriesFixture, makeClient } from './fixtures';

const { searchMock, getClientMock } = vi.hoisted(() => ({
  searchMock: vi.fn(),
  getClientMock: vi.fn(),
}));

vi.mock('next/navigation', () => ({
  useRouter: () => ({ push: vi.fn(), replace: vi.fn(), refresh: vi.fn() }),
  usePathname: () => '/',
}));

vi.mock('@/lib/api', () => ({
  getApiClient: () => getClientMock(),
}));

function client() {
  return makeClient({
    categories: {
      getCategories: async () => ({
        items: categoriesFixture,
        scope: 'IMPORTED_CFPB_SAMPLE',
      }),
    },
    search: { searchCases: searchMock },
  });
}

function codePointLength(value: string): number {
  return Array.from(value).length;
}

describe('Unicode code-point limits in the browser controls', () => {
  it('keeps a 50,001-code-point search narrative and blocks submit without an API call', async () => {
    getClientMock.mockReturnValue(client());
    const overLimit = '😀'.repeat(50_001);

    render(
      <AppProviders>
        <FindPrecedentsPage />
      </AppProviders>,
    );

    const textarea = screen.getByLabelText('Complaint narrative') as HTMLTextAreaElement;
    expect(textarea).not.toHaveAttribute('maxlength');
    fireEvent.change(textarea, { target: { value: overLimit } });
    expect(codePointLength(textarea.value)).toBe(50_001);

    fireEvent.click(screen.getByRole('button', { name: 'Find precedents' }));

    expect(
      screen.getByText('The narrative must be 50,000 characters or fewer.'),
    ).toBeInTheDocument();
    expect(searchMock).not.toHaveBeenCalled();
    expect(codePointLength(textarea.value)).toBe(50_001);
  });

  it.each([
    ['narrative', 'Complaint narrative', 50_000, false],
    ['handlingNotes', 'Handling notes', 10_000, false],
    ['evaluationNotes', 'Evaluation notes', 10_000, true],
    ['company', 'Company', 500, true],
    ['companyResponse', 'Company response', 2_000, true],
  ] as const)(
    'keeps an over-limit %s value intact and refuses to submit it',
    async (field, label, limit, optional) => {
      getClientMock.mockReturnValue(client());
      const onSubmit = vi.fn();
      const values = {
        ...emptyCaseForm(),
        product: 'Checking or savings account',
        issue: 'Managing an account',
        narrative: 'A valid narrative.',
        handlingNotes: 'Handled.',
        [field]: '😀'.repeat(limit + 1),
      };

      render(
        <AppProviders>
          <CaseForm
            mode="create"
            values={values}
            onChange={vi.fn()}
            onSubmit={onSubmit}
            onSuccess={vi.fn()}
            cancelHref="/cases"
          />
        </AppProviders>,
      );

      const control = screen.getByLabelText(
        optional ? `${label} (optional)` : label,
        { exact: true },
      ) as HTMLInputElement | HTMLTextAreaElement;
      expect(control).not.toHaveAttribute('maxlength');
      expect(codePointLength(control.value)).toBe(limit + 1);

      fireEvent.submit(control.closest('form')!);

      await waitFor(() =>
        expect(
          screen.getByText(new RegExp(`${limit.toLocaleString('en-US')} characters or fewer`)),
        ).toBeInTheDocument(),
      );
      expect(onSubmit).not.toHaveBeenCalled();
      expect(codePointLength(control.value)).toBe(limit + 1);
    },
  );
});
